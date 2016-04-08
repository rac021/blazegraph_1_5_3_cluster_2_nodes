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
 * Created on Oct 14, 2006
 */

package com.bigdata.quorum.zk;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import com.bigdata.quorum.Quorum;

/**
 * Test suite for the zookeeper integration for bigdata {@link Quorum}s.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class TestAll extends TestCase {

    /**
     * 
     */
    public TestAll() {
    }

    /**
     * @param arg0
     */
    public TestAll(String arg0) {
        super(arg0);
    }

    /**
     * Returns a test that will run each of the implementation specific test
     * suites in turn.
     */
    public static Test suite()
    {

        final TestSuite suite = new TestSuite("zookeeper quorum integration");

        // test for how to split a zpath into path components.
        suite.addTestSuite(TestSplitZPath.class);
        
        // test suite for deltas (added/removed) in (un)ordered sets.
        suite.addTestSuite(TestSetDifference.class);

        // @todo verify serializability and versioning of QuorumTokenState
        // @todo verify serializability and versioning of QuorumServiceState
        // @todo verify serializability and versioning of QuorumPipelineState
        
        // bootstrap test.
        suite.addTestSuite(TestZkQuorum.class);
        
        // unit tests for a singleton quorum.
        suite.addTestSuite(TestZkSingletonQuorumSemantics.class);
        
        // unit tests for a highly available quorum.
        suite.addTestSuite(TestZkHA3QuorumSemantics.class);
        
        return suite;

    }

}
