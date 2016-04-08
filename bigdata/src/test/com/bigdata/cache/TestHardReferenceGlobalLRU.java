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
 * Created on Sep 16, 2009
 */

package com.bigdata.cache;

import com.bigdata.rawstore.Bytes;


/**
 * Some unit tests for the {@link HardReferenceGlobalLRU}.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class TestHardReferenceGlobalLRU extends
        AbstractHardReferenceGlobalLRUTest {

    /**
     * 
     */
    public TestHardReferenceGlobalLRU() {
    }

    /**
     * @param name
     */
    public TestHardReferenceGlobalLRU(String name) {
        super(name);
    }

    protected void setUp() throws Exception {

        super.setUp();

        final long maximumBytesInMemory = 10 * Bytes.kilobyte;

        final int minimumCacheSetCapacity = 0;

        final int initialCacheCapacity = 16;

        final float loadFactor = .75f;

        lru = new HardReferenceGlobalLRU<Long, Object>(maximumBytesInMemory,
                minimumCacheSetCapacity, initialCacheCapacity, loadFactor);

    }

}
