package org.apache.solr.logging.logback;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.util.ContextInitializer;
import ch.qos.logback.classic.util.ContextSelectorStaticBinder;
import ch.qos.logback.core.CoreConstants;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.status.StatusUtil;
import ch.qos.logback.core.util.StatusPrinter;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.core.SolrResourceLoader;
import org.apache.solr.logging.LogWatcher;
import org.apache.solr.logging.LogWatcherConfig;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.helpers.Util;
import org.slf4j.spi.LoggerFactoryBinder;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LogbackWatcherTest {
  private LogWatcherConfig config;
  private SolrResourceLoader loader = null;

  /**
   * copy and rename logback-classic.jar:org.slf4j.impl.StaticLoggerBinder
   */
  private static class LogbackIniter implements LoggerFactoryBinder {

    /**
     * Declare the version of the SLF4J API this implementation is compiled
     * against. The value of this field is usually modified with each release.
     */
    // to avoid constant folding by the compiler, this field must *not* be final
    public static String REQUESTED_API_VERSION = "1.6"; // !final

    final static String NULL_CS_URL = CoreConstants.CODES_URL + "#null_CS";

    /**
     * The unique instance of this class.
     */
    private static LogbackIniter SINGLETON = new LogbackIniter();

    private static Object KEY = new Object();

    static {
      SINGLETON.init();
    }

    private boolean initialized = false;
    private LoggerContext defaultLoggerContext = new LoggerContext();
    private final ContextSelectorStaticBinder contextSelectorBinder = ContextSelectorStaticBinder
        .getSingleton();

    private LogbackIniter () {
      defaultLoggerContext.setName(CoreConstants.DEFAULT_CONTEXT_NAME);
    }

    public static LogbackIniter getSingleton () {
      return SINGLETON;
    }

    /**
     * Package access for testing purposes.
     */
    static void reset () {
      SINGLETON = new LogbackIniter();
      SINGLETON.init();
    }

    /**
     * Package access for testing purposes.
     */
    void init () {
      try {
        try {
          new ContextInitializer(defaultLoggerContext).autoConfig();
        } catch (JoranException je) {
          Util.report("Failed to auto configure default logger context", je);
        }
        // logback-292
        if (!StatusUtil.contextHasStatusListener(defaultLoggerContext)) {
          StatusPrinter.printInCaseOfErrorsOrWarnings(defaultLoggerContext);
        }
        contextSelectorBinder.init(defaultLoggerContext, KEY);
        initialized = true;
      } catch (Throwable t) {
        // we should never get here
        Util.report("Failed to instantiate [" + LoggerContext.class.getName()
            + "]", t);
      }
    }

    public ILoggerFactory getLoggerFactory () {
      if (!initialized) {
        return defaultLoggerContext;
      }

      if (contextSelectorBinder.getContextSelector() == null) {
        throw new IllegalStateException(
            "contextSelector cannot be null. See also " + NULL_CS_URL);
      }
      return contextSelectorBinder.getContextSelector().getLoggerContext();
    }

    public String getLoggerFactoryClassStr () {
      return contextSelectorBinder.getClass().getName();
    }

  }

  @BeforeClass
  public static void init_logback () {
    //init
    LogbackIniter.getSingleton();
  }

  @Before
  public void setUp () {
    config = new LogWatcherConfig(true, LogbackWatcher.class.getName(), null,
        50);
    loader = new SolrResourceLoader(".",
        Runtime.getRuntime().getClass().getClassLoader(), null);
  }

  private void sleep (long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  private LogWatcher createWatcher () {
    if (!config.isEnabled()) {
      //log.info("A LogWatcher is not enabled");
      return null;
    }

    LogWatcher logWatcher = loader.newInstance(config.getLoggingClass(), LogbackWatcher.class);

    if (logWatcher != null) {
      if (config.getWatcherSize() > 0) {
        //log.info("Registering Log Listener [{}]", logWatcher.getName());
        logWatcher.registerListener(config.asListenerConfig());
      }
    }

    return logWatcher;
  }

  private Logger getLogger (String logName) {
    return LogbackIniter.getSingleton().getLoggerFactory().getLogger(logName);
  }

  @Test
  public void testLogbackWatcher () {

    //Logger log = LoggerFactory.getLogger("testlogger");
    Logger log = getLogger("testlogger");
    LogWatcher watcher = createWatcher();

    assertEquals(watcher.getLastEvent(), -1);

    log.warn("This is a test message");

    assertTrue(watcher.getLastEvent() > -1);

    SolrDocumentList events = watcher.getHistory(-1, new AtomicBoolean());
    assertEquals(events.size(), 1);

    SolrDocument event = events.get(0);
    assertEquals(event.get("logger"), "testlogger");
    assertEquals(event.get("message"), "This is a test message");

    //test set watcherThreshold
    watcher.setThreshold("info");
    long last = watcher.getLastEvent();
    sleep(5);

    log.info("test info message");

    assertTrue(watcher.getLastEvent() > last);

    events = watcher.getHistory(last, new AtomicBoolean());
    assertEquals(events.size(), 1);

    event = events.get(0);
    assertEquals(event.get("logger"), "testlogger");//level
    assertEquals(event.get("message"), "test info message");
    assertTrue("info".equalsIgnoreCase(event.get("level").toString()));

    watcher.setThreshold("warn");
    last = watcher.getLastEvent();
    sleep(5);

    log.info("test another info message");

    assertTrue(watcher.getLastEvent() == last);
  }
}
