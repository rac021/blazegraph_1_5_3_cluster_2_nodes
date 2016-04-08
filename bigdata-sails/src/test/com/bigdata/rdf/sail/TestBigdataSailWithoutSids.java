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
 * Created on Sep 4, 2008
 */

package com.bigdata.rdf.sail;

import java.util.Properties;

import junit.extensions.proxy.ProxyTestSuite;
import junit.framework.Test;

import com.bigdata.rdf.sail.BigdataSail.Options;

/**
 * Test suite for the {@link BigdataSail} with statement identifiers enabled.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class TestBigdataSailWithoutSids extends AbstractBigdataSailTestCase {

    /**
     * 
     */
    public TestBigdataSailWithoutSids() {
    }

    public TestBigdataSailWithoutSids(String name) {
        super(name);
    }
    
    public static Test suite() {
        
        final TestBigdataSailWithoutSids delegate = new TestBigdataSailWithoutSids(); // !!!! THIS CLASS !!!!

        /*
         * Use a proxy test suite and specify the delegate.
         */

        final ProxyTestSuite suite = new ProxyTestSuite(delegate, "SAIL with Triples (no SIDs)");

        // test rewrite of RDF Value => BigdataValue for binding set and tuple expr.
        suite.addTestSuite(TestBigdataValueReplacer.class);

        // test pruning of variables not required for downstream processing.
        suite.addTestSuite(TestPruneBindingSets.class);

        // test of the search magic predicate
        suite.addTestSuite(TestSearchQuery.class);

        // test of high-level query on a graph with statements about statements.
        suite.addTestSuite(TestProvenanceQuery.class);

        suite.addTestSuite(TestUnions.class);
        
        suite.addTestSuite(TestInlineValues.class);
        
        suite.addTestSuite(TestConcurrentKBCreate.TestWithGroupCommit.class);
        suite.addTestSuite(TestConcurrentKBCreate.TestWithoutGroupCommit.class);

        suite.addTestSuite(TestTxCreate.class);

        suite.addTestSuite(TestChangeSets.class);

        // test suite for the history index.
        suite.addTestSuite(TestHistoryIndex.class);

		suite.addTestSuite(com.bigdata.rdf.sail.TestRollbacks.class);
		suite.addTestSuite(com.bigdata.rdf.sail.TestRollbacksTx.class);
        suite.addTestSuite(com.bigdata.rdf.sail.TestRollbacksTM.class);
        suite.addTestSuite(com.bigdata.rdf.sail.TestMROWTransactionsNoHistory.class);
        suite.addTestSuite(com.bigdata.rdf.sail.TestMROWTransactionsWithHistory.class);
		
        suite.addTestSuite(com.bigdata.rdf.sail.TestMillisecondPrecisionForInlineDateTimes.class);

        suite.addTestSuite(com.bigdata.rdf.sail.TestTicket275.class);
        suite.addTestSuite(com.bigdata.rdf.sail.TestTicket276.class);
        suite.addTestSuite(com.bigdata.rdf.sail.TestTicket422.class);
        suite.addTestSuite(com.bigdata.rdf.sail.TestTicket473.class);

        suite.addTestSuite(com.bigdata.rdf.sail.TestLexJoinOps.class);
        suite.addTestSuite(com.bigdata.rdf.sail.TestMaterialization.class);
        
        suite.addTestSuite(com.bigdata.rdf.sail.TestTicket610.class);
        suite.addTestSuite(com.bigdata.rdf.sail.TestTicket669.class);
        suite.addTestSuite(com.bigdata.rdf.sail.TestTicket693.class);
        
        suite.addTestSuite(com.bigdata.rdf.sail.TestInlineURIs.class);
        
        return suite;
        
    }
    
    @Override
    protected BigdataSail getSail(final Properties properties) {
        
        return new BigdataSail(properties);
        
    }

    public Properties getProperties() {

        final Properties properties = new Properties(super.getProperties());
/*
        properties.setProperty(Options.STATEMENT_IDENTIFIERS, "false");
        
        properties.setProperty(Options.QUADS, "false");
*/
        properties.setProperty(Options.TRIPLES_MODE, "true");
        
        return properties;
        
    }
    
    @Override
    protected BigdataSail reopenSail(final BigdataSail sail) {

        final Properties properties = sail.database.getProperties();

        if (sail.isOpen()) {

            try {

                sail.shutDown();

            } catch (Exception ex) {

                throw new RuntimeException(ex);

            }

        }
        
        return getSail(properties);
        
    }

}
