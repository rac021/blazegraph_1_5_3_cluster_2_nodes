/**

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
 * Created on Jun 26, 2006
 */
package com.bigdata.jini;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import com.bigdata.service.jini.AbstractServerTestCase;

/**
 * Aggregates tests in dependency order. The service tests require that Jini is
 * running, that you have specified a suitable security policy, etc. See
 * {@link AbstractServerTestCase} for <strong>required</strong> system
 * properties in order to run this test suite
 * 
 * @version $Id$
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 */
public class TestAll extends TestCase {

    public TestAll() {
    }

    public TestAll(String name) {
        super(name);
    }

    public static Test suite() {

        /*
         * log4j defaults to DEBUG which will produce simply huge amounts of
         * logging information when running the unit tests. Therefore we
         * explicitly set the default logging level to WARN. If you are using a
         * log4j configuration file then this is unlikely to interact with your
         * configuration, and in any case you can override specific loggers.
         */
        {

            final Logger log = Logger.getRootLogger();

            if (log.getLevel().equals(Level.DEBUG)) {

                log.setLevel(Level.WARN);

                log
                        .warn("Defaulting debugging level to WARN for the unit tests");

            }

        }

        final TestSuite suite = new TestSuite("jini");

        // jini configuration helpers.
        suite.addTest(com.bigdata.jini.util.TestAll.suite());

        // zookeeper client library (queues, locks, etc).
        suite.addTest(com.bigdata.zookeeper.TestAll.suite());

        // zookeeper quorum integration test suite.
        suite.addTest(com.bigdata.quorum.zk.TestAll.suite());

        // highly available Journal test suite running with zookeeper.
        suite.addTest(com.bigdata.journal.jini.ha.TestAll.suite());

        // concrete impls of bigdata services using jini.
        suite.addTest(com.bigdata.service.jini.TestAll.suite());

        // bigdata services manager test suite.
        suite.addTest(com.bigdata.jini.start.TestAll.suite());

        return suite;

    }

}
