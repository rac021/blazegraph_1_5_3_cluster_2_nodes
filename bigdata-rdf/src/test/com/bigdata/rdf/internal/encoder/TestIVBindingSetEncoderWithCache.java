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
 * Created on Feb 15, 2012
 */

package com.bigdata.rdf.internal.encoder;

import java.util.Collections;
import java.util.Map;

import com.bigdata.bop.BOp;
import com.bigdata.bop.BOpBase;
import com.bigdata.bop.IPredicate;
import com.bigdata.rawstore.IRawStore;
import com.bigdata.rawstore.SimpleMemoryRawStore;
import com.bigdata.rdf.internal.IV;
import com.bigdata.rdf.internal.IVCache;
import com.bigdata.rdf.model.BigdataValue;

/**
 * Test suite for {@link IVBindingSetEncoderWithIVCache}. This class supports an
 * {@link IV} to {@link BigdataValue} cache which provides lookup to resolve the
 * observed associations as reported by {@link IVCache#getValue()}.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id: TestIVBindingSetEncoderWithCache.java 6036 2012-02-17 14:07:48Z
 *          thompsonbry $
 */
public class TestIVBindingSetEncoderWithCache extends
        AbstractBindingSetEncoderTestCase {

    /**
     * 
     */
    public TestIVBindingSetEncoderWithCache() {
    }

    public TestIVBindingSetEncoderWithCache(String name) {
        super(name);
    }

    /**
     * Backing store for caches.
     */
    private IRawStore store = new SimpleMemoryRawStore();

    /**
     * Empty operator - will use defaults for various annotations.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private BOp op = new BOpBase(new BOp[] {}/* args */,
            (Map<String, Object>) (Map) Collections.singletonMap(
                    IPredicate.Annotations.RELATION_NAME,
                    new String[] { namespace })/* anns */);

    protected void setUp() throws Exception {
        
        super.setUp();
    
        // The encoder under test.
        encoder = new IVBindingSetEncoderWithIVCache(
                store, false/* filter */, op);
        
        // The decoder is the same object.
        decoder = (IVBindingSetEncoderWithIVCache) encoder;

    }
    
    protected void tearDown() throws Exception {
        
        super.tearDown();
        
        // Clear references.
        store = null;
        op = null;
        
    }
    
}
