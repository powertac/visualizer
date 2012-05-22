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

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executor;

import javax.annotation.Resource;
import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.Session;
import javax.jms.TextMessage;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.pool.PooledConnectionFactory;
import org.apache.log4j.Logger;
import org.powertac.common.Competition;
import org.powertac.common.XMLMessageConverter;
//import org.powertac.common.interfaces.VisualizerMessageListener;
//import org.powertac.common.interfaces.VisualizerProxy;
import org.powertac.visualizer.MessageDispatcher;
import org.powertac.visualizer.VisualizerApplicationContext;
import org.powertac.visualizer.beans.VisualizerBean;
import org.powertac.visualizer.interfaces.Initializable;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.Lifecycle;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.stereotype.Service;

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
  private VisualizerBean visualizerBean;

  //private boolean alreadyRegistered = false;
  private String serverUrl = "tcp://localhost:61616";
  private String queueName = "remote-visualizer";
  
  private LocalVisualizerProxy proxy;
  private boolean initialized = false;
  private boolean running = false;

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
    System.out.println("create and init proxy");
    proxy = new LocalVisualizerProxy();
    proxy.init(this);
  }
  
  // once-per-game initialization
  public void initOnce ()
  {
    //if (initialized)
    //  return;
    //initialized = true;
    
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
  }
  
  // shut down the queue at end-of-game, wait 30 seconds, go again.
  public void shutDown ()
  {
    System.out.println("shut down proxy");
    proxy.shutDown();
    Timer restartTimer = new Timer();
    restartTimer.schedule(new TimerTask () {
      @Override
      public void run () {
        init();
      }
    }, 30000l);
  }

  public void receiveMessage (Object msg)
  {
    // once-per-game initialization...
    if (msg instanceof Competition)
      initOnce();
    
    visualizerBean.incrementMessageCounter();

    if (msg != null) {
      log.debug("Counter: " + visualizerBean.getMessageCount()
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
  public void onMessage (Message message)
  {
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
    //log.info("onMessage(String) - received message:\n" + xml);
    Object message = converter.fromXML(xml);
    log.debug("onMessage(String) - received message of type " + message.getClass().getSimpleName());
    receiveMessage(message);
  }

  public void afterPropertiesSet () throws Exception
  {
    init();
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

  class LocalVisualizerProxy //implements VisualizerProxy
  {
    //TreeSet<VisualizerMessageListener> listeners =
    //  new TreeSet<VisualizerMessageListener>();
    
    VisualizerService host;

    LocalVisualizerProxy ()
    {
      super();
    }

    // set up the jms queue
    void init (VisualizerService host)
    {
      System.out.println("Server URL: " + getServerUrl() + ", queue: " + getQueueName());
      this.host = host;
      
      if (connectionFactory instanceof PooledConnectionFactory) {
        PooledConnectionFactory pooledConnectionFactory = (PooledConnectionFactory) connectionFactory;
        if (pooledConnectionFactory.getConnectionFactory() instanceof ActiveMQConnectionFactory) {
          ActiveMQConnectionFactory amqConnectionFactory = (ActiveMQConnectionFactory) pooledConnectionFactory
                  .getConnectionFactory();
          amqConnectionFactory.setBrokerURL(getServerUrl());
        }
      }

      // create the queue first
      boolean success = false;
      while (!success) {
        try {
          createQueue(getQueueName());
          success = true;
        }
        catch (JMSException e) {
          log.info("JMS message broker not ready - delay and retry");
          System.out.println("JMS message broker not ready - delay and retry");
          try {
            Thread.sleep(10000);
          }
          catch (InterruptedException e1) {
            // ignore exception
          }
        }
      }
      System.out.println("Queue " + getQueueName() + " created");

      // register host as listener
      DefaultMessageListenerContainer container = new DefaultMessageListenerContainer();
      container.setConnectionFactory(connectionFactory);
      container.setDestinationName(getQueueName());
      container.setMessageListener(host);
      container.setTaskExecutor(taskExecutor);
      container.afterPropertiesSet();
      container.start();
    }

    public void createQueue (String queueName) throws JMSException
    {
      // now we can create the queue
      Connection connection = connectionFactory.createConnection();
      Session session = connection.createSession(false,
                                                 Session.AUTO_ACKNOWLEDGE);
      session.createQueue(queueName);
      log.info("JMS Queue " + queueName + " created");
    }
    
    void shutDown ()
    {
      //container.shutdown();
    }
  }
}
