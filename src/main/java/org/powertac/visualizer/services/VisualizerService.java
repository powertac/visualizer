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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;

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
import org.powertac.common.msg.BrokerAccept;
import org.powertac.common.msg.BrokerAuthentication;
import org.powertac.common.msg.VisualizerStatusRequest;
import org.powertac.common.repo.DomainRepo;
import org.powertac.common.XMLMessageConverter;
import org.powertac.visualizer.MessageDispatcher;
import org.powertac.visualizer.VisualizerApplicationContext;
import org.powertac.visualizer.beans.VisualizerBean;
import org.powertac.visualizer.interfaces.Initializable;
import org.powertac.visualizer.services.VisualizerState.Event;
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
 * @author Jurica Babic, John Collins
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
  //private static enum State {init, loginWait, gameWait, gameReady, loggedIn};
  private long statePeriod = 60000l;
  private long maxMsgInterval = 120000l;
  private long lastMsgTime = 0l;
  private Timer tickTimer = null;
  
  // States
  private VisualizerState initial, loginWait, gameWait, gameReady, loggedIn;
  private VisualizerState currentState;
  
  // event queue
  private BlockingQueue<Event> eventQueue =
          new LinkedBlockingQueue<Event>();

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
    //System.out.println("viz: start state timer");
    tickTimer = new Timer();
    TimerTask stateTask = new TimerTask() {
      @Override
      public void run ()
      {
        System.out.println("viz: message count = " +
                           visualizerBean.getMessageCount());
        addEvent(Event.tick);
      }
    };
    tickTimer.schedule(stateTask, 10, statePeriod);
    runStates();
  }
  
  // convience functions for handling the queue
  private void addEvent (Event event)
  {
    try {
      eventQueue.put(event);
    }
    catch (InterruptedException e) {
      e.printStackTrace();
    }
  }
  
  private Event getEvent ()
  {
    try {
      return eventQueue.take();
    }
    catch (InterruptedException e) {
      e.printStackTrace();
      return Event.tick; // default event, harmless enough
    }
  }
  
  private void setCurrentState (VisualizerState newState)
  {
    currentState = newState;
    newState.entry();
  }
  
  // Run the viz state machine -- called from timer thread.
  private void runStates ()
  {
    initial = new VisualizerState () {
      @Override
      public void entry ()
      {
        System.out.println("viz state initial");
        if (null != proxy) {
          shutDown();
        }
        setCurrentState(loginWait);
      }
      @Override
      public void handleEvent (Event event)
      {
        if (event == Event.tick) {
          // safety valve
          setCurrentState(loginWait);          
        }
      }
    };
    
    loginWait = new VisualizerState () {
      @Override
      public void entry ()
      {
        System.out.println("viz state loginWait");
        tournamentLogin();
      }
      @Override
      public void handleEvent (Event event)
      {
        if (event == Event.noTm) {
          setCurrentState(gameWait);
        }
        else if (event == Event.accept){
          setCurrentState(gameReady);
        }
      }
    };
    
    gameWait = new VisualizerState () {
      @Override
      public void entry ()
      {
        System.out.println("viz state gameWait");
        gameLogin();
      }
      @Override
      public void handleEvent (Event event)
      {
        if (event == Event.vsr) {
          setCurrentState(loggedIn);
        }
        else if (event == Event.tick) {
          pingServer(); // try again
        }
      }
    };
    
    gameReady = new VisualizerState () {
      @Override
      public void entry ()
      {
        System.out.println("viz state gameReady");
        gameLogin();
      }
      @Override
      public void handleEvent (Event event)
      {
        if (event == Event.vsr) {
          setCurrentState(loggedIn);
        }
        else if (event == Event.tick) {
          pingServer(); // try again
        }
      }
    };

    loggedIn = new VisualizerState () {
      @Override
      public void entry ()
      {
        System.out.println("viz state loggedIn");
      }
      @Override
      public void handleEvent (Event event)
      {
        if (event == Event.simEnd) {
          setCurrentState(initial);
        }
        else if (event == Event.tick && isInactive()) {
          setCurrentState(initial);
        }
      }
    };
    
    setCurrentState(initial);
    while (true) {
      currentState.handleEvent(getEvent());
    }
  }

  // Logs into the tournament manager to get the queue name for the
  // upcoming session
  private void tournamentLogin ()
  {
    //System.out.println("Tournament URL='" + tournamentUrl + "'");
    if (tournamentUrl.isEmpty()) {
      // No TM, just connect to server
      addEvent(Event.noTm);
      return;
    }
    String urlString = tournamentUrl + visualizerLoginContext +
            "?machineName=" + machineName;
    System.out.println("viz: tourney url=" + urlString);
    URL url;
    boolean tryAgain = true;
    while (tryAgain) {
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
          System.out.println("viz: Retry in " + checkRetry + " seconds");
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

          System.out.printf("viz: Login message receieved:  queueName=%s, serverQueue=%s\n",
                            queueName, serverQueue);
          addEvent(Event.accept);
          tryAgain = false;
        }
        else {
          // this is not working
          System.out.println("Invalid response from TS");
        }
      }
      catch (Exception e) {
        // should we have an event here?
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
    pingServer();
  }
  
  private void pingServer ()
  {
    proxy.sendMessage(new VisualizerStatusRequest());
  }

  private boolean isInactive () {
    long now = new Date().getTime();
    long silence = now - lastMsgTime;
    if (silence > maxMsgInterval) {
      // declare inactivity
      System.out.println("viz: Inactivity declared");
      return true;
    }
    return false;
  }

  // once-per-game initialization
  public void initOnce ()
  {
    //if (initialized)
    //  return;
    initialized = true;
    
    System.out.println("viz: initOnce()");
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
  }
  
  // shut down the queue at end-of-game, wait a few seconds, go again.
  public void shutDown ()
  {
    System.out.println("viz: shut down proxy");

    proxy.shutDown();
    proxy = null; // force re-creation
    try {
      Thread.sleep(5000); // wait for sim process to quit
    }
    catch (InterruptedException e) {
      e.printStackTrace();
    }
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
      //log.debug("Counter: " + visualizerBean.getMessageCount()
      //          + ", Got message: " + msg.getClass().getName());
      //System.out.println("Counter: " + visualizerBean.getMessageCount()
      //          + ", Got message: " + msg.getClass().getName());
      dispatcher.routeMessage(msg);
    }
    else {
      System.out.println("Counter:" + visualizerBean.getMessageCount()
               + " Received message is NULL!");
    }
    // end-of-game check
    if (!running && visualizerBean.isRunning()) {
      running = true;
    }
    if (running && visualizerBean.isFinished()) {
      System.out.println("viz: Game finished");
      addEvent(Event.simEnd);
    }      
  }

  // JMS message input processing
  @Override
  public void onMessage (Message message)
  {
    lastMsgTime = new Date().getTime();
    //System.out.println("onMessage");
    if (message instanceof TextMessage) {
      try {
        //System.out.println("onMessage: text");
        onMessage(((TextMessage) message).getText());
      } catch (JMSException e) {
        System.out.println("ERROR: viz failed to extract text from TextMessage: " + e.toString());
      }
    }
  }

  // runs in JMS thread
  private void onMessage (String xml) {
    log.info("onMessage(String) - received message:\n" + xml);
    Object message = converter.fromXML(xml);
    log.debug("onMessage(String) - received message of type " + message.getClass().getSimpleName());
    if (message instanceof VisualizerStatusRequest) {
      addEvent(Event.vsr);
    }
    else if (message instanceof BrokerAccept ||
             message instanceof BrokerAuthentication) {
      // hack to ignore these
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
      System.out.println("viz: Server URL: " + getServerUrl() + ", queue: " + getQueueName());
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
      try {
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
      catch (Exception e) {
        System.out.println("Exception " + e.toString() +
                           " sending message - ignoring");
      }
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
