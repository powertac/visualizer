/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an
 * "AS IS" BASIS,  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */

package org.powertac.visualizer.services;

import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.Date;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executor;

import javax.annotation.Resource;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.pool.PooledConnectionFactory;
import org.apache.log4j.Logger;
import org.powertac.common.Competition;
import org.powertac.common.msg.VisualizerStatusRequest;
import org.powertac.common.repo.DomainRepo;
import org.powertac.common.XMLMessageConverter;
import org.powertac.visualizer.MessageDispatcher;
import org.powertac.visualizer.VisualizerApplicationContext;
import org.powertac.visualizer.beans.VisualizerBean;
import org.powertac.visualizer.interfaces.Initializable;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessageCreator;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

/**
 * Main Visualizer service. Its main purpose is to register with Visualizer
 * proxy and to receive messages from simulator.
 * 
 * @author Jurica Babic
 * 
 */

@Service
public class VisualizerService
  implements MessageListener, InitializingBean
{
  static private Logger log = Logger.getLogger(VisualizerService.class
          .getName());

  @Resource(name="jmsFactory")
  private ConnectionFactory connectionFactory;
  
  @Autowired
  private Executor taskExecutor;
  
  @Autowired
  XMLMessageConverter converter;
  
  @Autowired
  JmsTemplate template;

  @Autowired
  private VisualizerBean visualizerBean;

  private String tournamentUrl = "";
  private String visualizerLoginContext = "";
  private String machineName = "";
  private String serverUrl = "tcp://localhost:61616";
  private String serverQueue = "serverInput";
  private String queueName = "remote-visualizer";

  // visualizer interaction
  private LocalVisualizerProxy proxy;
  private boolean initialized = false;
  private boolean running = false;
  
  // state parameters
  private static enum State {init, loginWait, gameWait, gameReady, loggedIn};
  private long statePeriod = 60000l;
  private long maxMsgInterval = 120000l;
  private long lastMsgTime = 0l;
  private State state = State.init;
  private Timer stateTimer = null;

  @Autowired
  private MessageDispatcher dispatcher;

  public VisualizerService ()
  {
    super();
  }

  /**
   * Should be called before simulator run in order to prepare/reset
   * Visualizer beans and register with the new simulator instance.
   */
  public void init ()
  {
    System.out.println("start state timer");
    stateTimer = new Timer();
    TimerTask stateTask = new TimerTask() {
      @Override
      public void run ()
      {
        checkState();  
      }
    };
    stateTimer.schedule(stateTask, statePeriod, statePeriod);
  }
  
  // Run the viz state machine
  private void checkState ()
  {
    if (state == State.init) {
      // try to log in to the TM
      state = State.loginWait;
      tournamentLogin();
    }
    else if (state == State.gameWait) {
      // no TM, log in to game directly
      gameLogin();
    }
    else if (state == State.gameReady) {
      // log into game
      gameLogin();
    }
    else if (state == State.loginWait) {
      // nothing to do here
    }
    else if (state == State.loggedIn) {
      // check for server inactivity
      checkInactivity();
    }
  }

  // Logs into the tournament manager to get the queue name for the
  // upcoming session
  private void tournamentLogin ()
  {
    if (tournamentUrl.isEmpty()) {
      // No TM, just connect to server
      state = State.gameWait;
      return;
    }
    String urlString = tournamentUrl + visualizerLoginContext +
            "?machineName=" + machineName;
    System.out.println("url=" + urlString);
    URL url;
    while (state == State.loginWait) {
      try {
        url = new URL(urlString);
        URLConnection conn = url.openConnection();
        InputStream input = conn.getInputStream();
        System.out.println("Parsing message..");
        DocumentBuilderFactory docBuilderFactory = DocumentBuilderFactory
                .newInstance();
        DocumentBuilder docBuilder = docBuilderFactory.newDocumentBuilder();
        Document doc = docBuilder.parse(input);

        doc.getDocumentElement().normalize();

        // Two different message types
        Node retryNode = doc.getElementsByTagName("retry").item(0);
        Node loginNode = doc.getElementsByTagName("login").item(0);

        if (retryNode != null) {
          String checkRetry = retryNode.getFirstChild()
                  .getNodeValue();
          //log.info("Retry in " + checkRetry
          //         + " seconds");
          System.out.println("Retry in " + checkRetry + " seconds");
          // Received retry message; spin and try again
          try {
            Thread.sleep(Integer.parseInt(checkRetry) * 1000);
          }
          catch (InterruptedException e) {
            e.printStackTrace();
          }
        }
        else if (loginNode != null) {
          log.info("Login response received! ");

          String checkQueue = 
                  doc.getElementsByTagName("queueName")
                  .item(0).getFirstChild().getNodeValue();
          queueName = checkQueue;
          String checkSvrQueue = 
                  doc.getElementsByTagName("serverQueue")
                  .item(0).getFirstChild().getNodeValue();
          serverQueue = checkSvrQueue;
          log.info("queueName=" + checkQueue);

          System.out.printf("Login message receieved:  queueName=%s, serverQueue=%s\n",
                            queueName, serverQueue);
          state = State.gameReady;
        }
        else {
          // this is not working
          System.out.println("Invalid response from TS");
          state = State.init;
        }
      }
      catch (Exception e) {
        state = State.init;
        e.printStackTrace();
      }
    }    
  }
  
  // Attempt to log into a game.
  private void gameLogin ()
  {
    if (null == proxy) {
      // no proxy yet for this game
      proxy = new LocalVisualizerProxy();
      proxy.init(this);
    }
    VisualizerStatusRequest rq = new VisualizerStatusRequest();
    proxy.sendMessage(rq);
  }

  private void checkInactivity () {
    long now = new Date().getTime();
    long silence = now - lastMsgTime;
    if (silence > maxMsgInterval) {
      // declare inactivity
      System.out.println("Inactivity declared");
      shutDown();
    }
  }

  // once-per-game initialization
  public void initOnce ()
  {
    //if (initialized)
    //  return;
    initialized = true;
    
    System.out.println("initOnce()");
    visualizerBean.newRun();

    // visualizerLogService.startLog(visualizerBean.getVisualizerRunCount());

    // registrations for message listeners:
    List<Initializable> initializers =
      VisualizerApplicationContext.listBeansOfType(Initializable.class);
    for (Initializable init: initializers) {
      log.debug("initializing..." + init.getClass().getName());
      init.initialize();
    }
    
    List<DomainRepo> repos =
  	      VisualizerApplicationContext.listBeansOfType(DomainRepo.class);
    for (DomainRepo repo: repos) {
      log.debug("recycling..." + repos.getClass().getName());
      repo.recycle();
    }
    //startWatchdog();
  }
  
  // shut down the queue at end-of-game, wait 30 seconds, go again.
  public void shutDown ()
  {
    System.out.println("shut down proxy");

    proxy.shutDown();
    state = State.init;
    proxy = null; // force re-creation
  }

  public void receiveMessage (Object msg)
  {
    // once-per-game initialization...
    if (msg instanceof Competition) {
      // Competition must be first message. If we see something else first,
      // it's an error.
      initOnce();
    }
    else if (!initialized) {
      System.out.println("ERROR: msg of type " + msg.getClass().getName() +
                         ", but not initialized. Ignoring.");
      return;
    }
    
    visualizerBean.incrementMessageCounter();

    if (msg != null) {
      log.debug("Counter: " + visualizerBean.getMessageCount()
                + ", Got message: " + msg.getClass().getName());
      System.out.println("Counter: " + visualizerBean.getMessageCount()
                + ", Got message: " + msg.getClass().getName());
      dispatcher.routeMessage(msg);
    }
    else {
      log.warn("Counter:" + visualizerBean.getMessageCount()
               + " Received message is NULL!");
    }
    // end-of-game check
    if (!running && visualizerBean.isRunning()) {
      running = true;
    }
    if (running && visualizerBean.isFinished()) {
      System.out.println("Game finished");
      shutDown();
    }      
  }

  // JMS message input processing
  @Override
  public void onMessage (Message message)
  {
    lastMsgTime = new Date().getTime();
    if (message instanceof TextMessage) {
      try {
        log.debug("onMessage(Message) - receiving a message");
        onMessage(((TextMessage) message).getText());
      } catch (JMSException e) {
        log.error("failed to extract text from TextMessage", e);
      }
    }
  }

  private void onMessage (String xml) {
    log.info("onMessage(String) - received message:\n" + xml);
    Object message = converter.fromXML(xml);
    log.debug("onMessage(String) - received message of type " + message.getClass().getSimpleName());
    if (message instanceof VisualizerStatusRequest) {
      if (state == State.gameReady || state == State.gameWait) {
        state = State.loggedIn;
      }
    }
    else {
      receiveMessage(message);
    }
  }

  @Override
  public void afterPropertiesSet () throws Exception
  {
    Timer initTimer = new Timer();
    // delay to let deployment complete
    initTimer.schedule(new TimerTask () {
      @Override
      public void run () {
        init();
      }
    }, 20000l);
  }
  
  // URL and queue name methods
  public String getQueueName ()
  {
    return queueName;
  }
  
  public void setQueueName (String newName)
  {
    queueName = newName;
  }
  
  public String getServerUrl ()
  {
    return serverUrl;
  }
  
  public void setServerUrl (String newUrl)
  {
    serverUrl = newUrl;
  }
  
  public String getTournamentUrl ()
  {
    return tournamentUrl;
  }
  
  public void setTournamentUrl (String newUrl)
  {
    tournamentUrl = newUrl;
  }
  
  public String getVisualizerLoginContext ()
  {
    return visualizerLoginContext;
  }
  
  public void setVisualizerLoginContext (String newContext)
  {
    visualizerLoginContext = newContext;
  }

  public String getMachineName ()
  {
    return machineName;
  }

  public void setMachineName (String name)
  {
    machineName = name;
  }

  // ------------ Local proxy implementation -------------
  
  class LocalVisualizerProxy //implements VisualizerProxy
  {
    //TreeSet<VisualizerMessageListener> listeners =
    //  new TreeSet<VisualizerMessageListener>();
    
    VisualizerService host;
    boolean connectionOpen = false;
    DefaultMessageListenerContainer container;

    LocalVisualizerProxy ()
    {
      super();
    }

    // set up the jms queue
    void init (VisualizerService host)
    {
      System.out.println("Server URL: " + getServerUrl());
      this.host = host;
      
      if (connectionFactory instanceof PooledConnectionFactory) {
        PooledConnectionFactory pooledConnectionFactory = (PooledConnectionFactory) connectionFactory;
        if (pooledConnectionFactory.getConnectionFactory() instanceof ActiveMQConnectionFactory) {
          ActiveMQConnectionFactory amqConnectionFactory = (ActiveMQConnectionFactory) pooledConnectionFactory
                  .getConnectionFactory();
          amqConnectionFactory.setBrokerURL(getServerUrl());
        }
      }

      // register host as listener
      container = new DefaultMessageListenerContainer();
      container.setConnectionFactory(connectionFactory);
      container.setDestinationName(getQueueName());
      container.setMessageListener(host);
      container.setTaskExecutor(taskExecutor);
      container.afterPropertiesSet();
      container.start();
      
      connectionOpen = true;
    }

    public void sendMessage (Object msg)
    {
      final String text = converter.toXML(msg);
      template.send(serverQueue,
                    new MessageCreator() {
        @Override
        public Message createMessage (Session session) throws JMSException {
          TextMessage message = session.createTextMessage(text);
          return message;
        }
      });
    }

    public synchronized void shutDown ()
    {
      Runnable callback = new Runnable() {
        @Override
        public void run ()
        {
          closeConnection();
        }
      };
      container.stop(callback);
      
      while (connectionOpen) {
        try {
          wait();
        }
        catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
    }

    private synchronized void closeConnection ()
    {
      //session.close();
      //connection.close();
      connectionOpen = false;
      notifyAll();
    }
  }
}
