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
package com.bigdata.zookeeper;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

/**
 * Aggregates tests in dependency order.
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

        final TestSuite suite = new TestSuite("zookeeper client library");

//        // test suite for zookeeper session expiration semantics.
//        suite.addTestSuite(TestZookeeperSessionSemantics.class);

        // test ability to handle an expired session.
        suite.addTestSuite(TestZookeeperAccessor.class);

        // test suite for EPHEMERAL_SEQUENTIAL semantics.
        suite.addTestSuite(TestEphemeralSemantics.class);

        // a watcher for a znode to be created.
        suite.addTestSuite(TestZNodeCreatedWatcher.class);
        
        // a watcher for a znode to be deleted.
        suite.addTestSuite(TestZNodeDeletedWatcher.class);

        // a watcher for new children of some znode.
        suite.addTestSuite(TestUnknownChildrenWatcher.class);

        // a global synchronous lock test suite.
        suite.addTestSuite(TestZLockImpl.class);

        // a watcher for a dynamic hierarchy of znodes.
        suite.addTestSuite(TestHierarchicalZNodeWatcher.class);

        // a barrier pattern.
        suite.addTestSuite(TestZooBarrier.class);

        // a queue pattern.
        suite.addTestSuite(TestZooQueue.class);

        // an election pattern.
        suite.addTestSuite(TestZooElection.class);

        return suite;
        
    }
    
}
