/*

Copyright (C) SYSTAP, LLC 2006-2015.  All rights reserved.

Contact:
     SYSTAP, LLC
     2501 Calvert ST NW #106
     Washington, DC 20008
     licenses@systap.com

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; version 2 of the License.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/
/*
 * Created on Jan 11, 2009
 */

package com.bigdata.jini.start;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import junit.framework.TestCase2;
import net.jini.admin.Administrable;
import net.jini.config.Configuration;
import net.jini.config.ConfigurationProvider;
import net.jini.core.lookup.ServiceItem;
import net.jini.core.lookup.ServiceRegistrar;
import net.jini.core.lookup.ServiceTemplate;
import net.jini.discovery.LookupDiscoveryManager;
import net.jini.lookup.ServiceDiscoveryManager;

import com.bigdata.jini.start.config.JiniCoreServicesConfiguration;
import com.bigdata.jini.start.config.JiniCoreServicesConfiguration.Options;
import com.bigdata.jini.start.process.JiniCoreServicesProcessHelper;
import com.bigdata.jini.util.ConfigMath;
import com.bigdata.service.jini.JiniClientConfig;
import com.bigdata.service.jini.util.JiniServicesHelper;

/**
 * Test suite for the {@link JiniCoreServicesProcessHelper}
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class TestJiniCoreServicesProcessHelper extends TestCase2 {

    /**
     * 
     */
    public TestJiniCoreServicesProcessHelper() {
     
    }

    /**
     * @param arg0
     */
    public TestJiniCoreServicesProcessHelper(String arg0) {
        super(arg0);
     
    }

    /**
     * The configuration file used the unit tests.
     */
    protected final String configFile = "file:src/test/resources/com/bigdata/jini/start/testjini.config";

    /**
     * The configuration read from that file with any overrides applied.
     */
    protected Configuration config;

    protected final MockListener listener = new MockListener();
    
    /**
     * Reads the {@link #config} from the {@link #configFile}.
     * <p>
     * Note: You can specify JINI_HOME in your environment in order to override
     * the location where jini is installed on your machine.
     */
    protected void setUp() throws Exception {

        final String[] args;
        {

            final String home = System.getenv("JINI_HOME");

            if (home == null) {

                args = new String[] { configFile };

            } else {

                /*
                 * Overrides the serviceDir to your jini install location.
                 */

                args = new String[] {
                        configFile,
                        JiniCoreServicesConfiguration.Options.NAMESPACE + "."
                                + Options.SERVICE_DIR + "="
                                + ConfigMath.q(home) };

            }
            
        }
        
        // read the configuration, applying the override if set above.
        config = ConfigurationProvider.getInstance(args);

//        logLevel = jiniLog.getLevel();
//
//        jiniLog.addHandler(new Handler(){
//
//            @Override
//            public void close() throws SecurityException {
//                // TODO Auto-generated method stub
//                
//            }
//
//            @Override
//            public void flush() {
//                // TODO Auto-generated method stub
//                
//            }
//
//            @Override
//            public void publish(LogRecord record) {
//                System.err.println("jini: "+record);
//            }});
//        
//        System.err.println("logLevel="+logLevel);
//        
//        // turn on lots of logging.
//        jiniLog.setLevel(
//                java.util.logging.Level.ALL);

    }
    
//    final static Logger jiniLog = Logger.getLogger("net.jini.discovery.LookupDiscovery");
//    
//    private java.util.logging.Level logLevel;
//
//    public void tearDown() throws Exception {
//        
//        // restore the log level.
//        jiniLog.setLevel(logLevel);
//        
//    }
    
    /**
     * @todo this is not really a unit test yet - more of a tool to helper debug
     *       the behavior when starting and (trying to) kill the jini core
     *       services.
     *       <p>
     *       The main problem with testability is that I have not figured out
     *       how to kill jini programmatically. One consequence is that this
     *       "test" will not terminate if it starts a jini instance until you
     *       close the jini instance in the gui.
     * 
     * @see JiniCoreServicesProcessHelper#startCoreServices(Configuration,
     *      IServiceListener)
     */
    public void test_findStartKill() throws Exception {

        final JiniCoreServicesConfiguration serviceConfig = new JiniCoreServicesConfiguration(
                config);
        
        final JiniClientConfig clientConfig = new JiniClientConfig(
                null/* class */, config);
        
        // make sure jini is not running before we start this test.
        assertFalse("Jini already running: locators="
                + Arrays.toString(clientConfig.locators), JiniServicesHelper
                .isJiniRunning(clientConfig.groups, clientConfig.locators, 500,
                        TimeUnit.MILLISECONDS));

        boolean serviceStarted = 
            JiniCoreServicesProcessHelper.startCoreServices(config, listener);
        String testName = (this.getClass()).getSimpleName();
        if(serviceStarted) {
            // Find and shutdown lookup service started above
            ServiceDiscoveryManager sdm = 
                new ServiceDiscoveryManager
                    (new LookupDiscoveryManager(clientConfig.groups,
                                                clientConfig.locators, null),
                     null);
            Class[] types = new Class[] { ServiceRegistrar.class };
            ServiceTemplate tmpl = new ServiceTemplate(null, types, null);
            ServiceItem regItem = sdm.lookup(tmpl, null, 5L*1000L);
            if(regItem == null) {
                System.err.println
                    ("WARNING ["+testName+"]: lookup service started but "
                     +"could not discover it for shutdown");
            } else {
                ServiceRegistrar reg = (ServiceRegistrar)(regItem.service);
                List<String> groupsList = Arrays.asList(reg.getGroups());
                System.err.println
                    ("INFO ["+testName+"]: lookup service started "
                     +"[groups="+groupsList+"] - shutting it down");
                Object admin  = ((Administrable)reg).getAdmin();
                ((com.sun.jini.admin.DestroyAdmin)admin).destroy();
                System.err.println
                    ("INFO ["+testName+"]: lookup service started and "
                     +"destroyed - [groups="+groupsList+"]");
            }
        }

        // Shutdown the httpd class server
        String httpdStopCmd = 
            (String)config.getEntry("jini", "httpdStopCmd", String.class,
                                    null /*force exception if not in config*/);
        System.err.println
            ("INFO ["+testName+"]: shutdown class server ["+httpdStopCmd+"]");
        Runtime.getRuntime().exec(httpdStopCmd);

        assertTrue(serviceStarted);

//
//        final JiniCoreServicesStarter<JiniCoreServicesProcessHelper> serviceStarter = serviceConfig
//                .newServiceStarter(listener);
//
//        // start jini.
//        final JiniCoreServicesProcessHelper processHelper = serviceStarter.call();
//
//        // make sure jini is running.
//        assertTrue(JiniServicesHelper.isJiniRunning(clientConfig.groups,
//                clientConfig.locators, 500, TimeUnit.MILLISECONDS));
//        
//        // destroy jini.
//        processHelper.kill();
        
    }
    
}
