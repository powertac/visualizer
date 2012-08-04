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

import java.io.IOException;
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
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.pool.PooledConnectionFactory;
import org.apache.log4j.FileAppender;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
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
  implements MessageListener, InitializingBean, ServletContextListener
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
  private long tickPeriod = 30000l; // 30 sec
  private long maxMsgInterval = 120000l; // 2 min
  private long maxGameReadyInterval = 300000l; // 5 min
  private long gameReadyAt = 0l;
  private long lastMsgTime = 0l;
  private boolean runningStates = true;
  
  // States
  private VisualizerState initial, loginWait, gameWait, gameReady, loggedIn;
  private VisualizerState currentState;
  
  // event queue
  private BlockingQueue<Event> eventQueue = new LinkedBlockingQueue<Event>();
  
  // message queue
  private BlockingQueue<Object> messageQueue = new LinkedBlockingQueue<Object>();

  // Timers, Threads and Runnables that may need to be killed
  private Timer tickTimer = null;
  private TimerTask tickTask = null;
  private Thread messageFeeder = null;
  private TimerTask stateTask = null;
  
  
  @Autowired
  private MessageDispatcher dispatcher;

  public VisualizerService ()
  {
    super();
  }

  /**
   * Called on initialization to start message feeder and state machine.
   */
  public void init ()
  {
    // Start the logger
    Logger root = Logger.getRootLogger();
    root.removeAllAppenders();
    try {
      PatternLayout logLayout = new PatternLayout("%r %-5p %c{2}: %m%n");
      FileAppender logFile
          = new FileAppender(logLayout,
                             ("log/" + machineName + "-viz.log"),
                             false);
      root.addAppender(logFile);
    }
    catch (IOException ioe) {
      log.info("Can't open log file");
      System.exit(0);
    }
    
    // Start the message feeder
    messageFeeder = new Thread(messagePump);
    messageFeeder.setDaemon(true);
    messageFeeder.start();

    // Start the state machine
    tickTimer = new Timer(true);
    TimerTask stateTask = new TimerTask() {
      @Override
      public void run ()
      {
        log.debug("message count = " + visualizerBean.getMessageCount() +
                  ", queue size = " + messageQueue.size());
        putEvent(Event.tick);
      }
    };
    tickTimer.schedule(stateTask, 10, tickPeriod);
    runStates();
  }

  // ServletContextListener callbacks
  @Override
  public void contextInitialized (ServletContextEvent sce)
  {
    // Nothing to do here    
  }

  // Wipe out timers and threads when servlet context is destroyed.
  @Override
  public void contextDestroyed (ServletContextEvent sce)
  {
    log.info("contextDestroyed - kill threads and tasks");
    // Kill the tick timer
    if (null != tickTimer) {
      tickTimer.cancel();
    }
    tickTask.cancel();
    
    // Kill the state machine
    runningStates = false;
    putEvent(Event.quit);

    // Kill the message pump
    messageFeeder.interrupt();
    try {
      messageFeeder.join();
    }
    catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  
  // convience functions for handling the event queue
  private void putEvent (Event event)
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
    runningStates = true;
    
    initial = new VisualizerState () {
      @Override
      public void entry ()
      {
        log.info("state initial");
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
        log.info("state loginWait");
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
        log.info("state gameWait");
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
        log.info("state gameReady");
        gameReadyAt = new Date().getTime();
        gameLogin();
      }
      @Override
      public void handleEvent (Event event)
      {
        if (event == Event.vsr) {
          setCurrentState(loggedIn);
        }
        else if (event == Event.tick) {
          long now = new Date().getTime();
          // limit harrassment of running game
          if (now > (gameReadyAt + maxGameReadyInterval)) {
            setCurrentState(initial);
          }
          else {
            pingServer(); // try again
          }
        }
      }
    };

    loggedIn = new VisualizerState () {
      @Override
      public void entry ()
      {
        log.info("state loggedIn");
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
    while (runningStates) {
      currentState.handleEvent(getEvent());
    }
  }

  // Logs into the tournament manager to get the queue name for the
  // upcoming session
  private void tournamentLogin ()
  {
    //log.info("Tournament URL='" + tournamentUrl + "'");
    if (tournamentUrl.isEmpty()) {
      // No TM, just connect to server
      putEvent(Event.noTm);
      return;
    }
    String urlString = tournamentUrl + visualizerLoginContext +
            "?machineName=" + machineName;
    log.info("tourney url=" + urlString);
    URL url;
    boolean tryAgain = true;
    while (tryAgain) {
      try {
        url = new URL(urlString);
        URLConnection conn = url.openConnection();
        InputStream input = conn.getInputStream();
        log.info("Parsing message..");
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
          log.info("Retry in " + checkRetry + " seconds");
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
          putEvent(Event.accept);
          tryAgain = false;
        }
        else {
          // this is not working
          log.info("Invalid response from TS");
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
    log.info("Ping sim server");
    proxy.sendMessage(new VisualizerStatusRequest());
  }

  private boolean isInactive () {
    long now = new Date().getTime();
    long silence = now - lastMsgTime;
    if (silence > maxMsgInterval) {
      // declare inactivity
      log.info("Inactivity declared");
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
    
    log.info("initOnce()");
    visualizerBean.newRun();

    // visualizerLogService.startLog(visualizerBean.getVisualizerRunCount());

    dispatcher.initialize();
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
    // no longer initialized
    initialized = false;
    
    log.info("shut down proxy");

    proxy.shutDown();
    proxy = null; // force re-creation
    try {
      Thread.sleep(5000); // wait for sim process to quit
    }
    catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  // ---------------- Message handling ------------------
  // Pumps messages from incoming JMS messages into the visualizer in
  // a single thread. The queue avoids potential race conditions on 
  // input.
  private Runnable messagePump = new Runnable() {
    @Override
    public void run ()
    {
      while (true) {
        Object msg = getMessage();
        receiveMessage(msg);
      }
    }
  };

  // convience functions for handling the event queue
  private void putMessage (Object message)
  {
    try {
      messageQueue.put(message);
    }
    catch (InterruptedException e) {
      e.printStackTrace();
    }
  }
  
  private Object getMessage ()
  {
    try {
      return messageQueue.take();
    }
    catch (InterruptedException e) {
      e.printStackTrace();
      return null;
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
      log.info("ERROR: msg of type " + msg.getClass().getName() +
                         ", but not initialized. Ignoring.");
      return;
    }
    
    visualizerBean.incrementMessageCounter();

    if (msg != null) {
      //log.debug("Counter: " + visualizerBean.getMessageCount()
      //          + ", Got message: " + msg.getClass().getName());
      //log.info("Counter: " + visualizerBean.getMessageCount()
      //          + ", Got message: " + msg.getClass().getName());
      dispatcher.routeMessage(msg);
    }
    else {
      log.info("Counter:" + visualizerBean.getMessageCount()
               + " Received message is NULL!");
    }
    // end-of-game check
    if (!running && visualizerBean.isRunning()) {
      running = true;
    }
    if (running && visualizerBean.isFinished()) {
      log.info("Game finished");
      putEvent(Event.simEnd);
    }      
  }

  // JMS message input processing
  @Override
  public void onMessage (Message message)
  {
    lastMsgTime = new Date().getTime();
    //log.info("onMessage");
    if (message instanceof TextMessage) {
      try {
        //log.info("onMessage: text");
        onMessage(((TextMessage) message).getText());
      } catch (JMSException e) {
        log.info("ERROR: viz failed to extract text from TextMessage: " + e.toString());
      }
    }
  }

  // runs in JMS thread
  private void onMessage (String xml) {
    log.debug("onMessage(String) - received message:\n" + xml);
    Object message = converter.fromXML(xml);
    log.debug("onMessage(String) - received message of type " + message.getClass().getSimpleName());
    if (message instanceof VisualizerStatusRequest) {
      log.info("Received vsr");
      putEvent(Event.vsr);
    }
    else if (message instanceof BrokerAccept ||
             message instanceof BrokerAuthentication) {
      // hack to ignore these
    }
    else {
      putMessage(message);
    }
  }

  @Override
  public void afterPropertiesSet () throws Exception
  {
    Timer initTimer = new Timer(true);
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
      log.info("Server URL: " + getServerUrl() + ", queue: " + getQueueName());
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
        log.warn("Exception " + e.toString() +
                           " sending message - ignoring");
      }
    }

    public synchronized void shutDown ()
    {
      final LocalVisualizerProxy proxy = this;
      Runnable callback = new Runnable() {
        @Override
        public void run ()
        {
          proxy.closeConnection();
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
