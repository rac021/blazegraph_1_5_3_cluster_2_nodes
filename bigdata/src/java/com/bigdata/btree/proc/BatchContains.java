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
 * Created on Feb 12, 2007
 */

package com.bigdata.btree.proc;

import com.bigdata.btree.IIndex;
import com.bigdata.btree.ISimpleBTree;
import com.bigdata.btree.proc.AbstractKeyArrayIndexProcedure.ResultBitBuffer;
import com.bigdata.btree.raba.codec.IRabaCoder;

/**
 * Batch existence test operation. Existence tests SHOULD be used in place of
 * lookup tests to determine key existence if null values are allowed in an
 * index (lookup will return a null for both a null value and the absence of a
 * key in the index).
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 */
public class BatchContains extends AbstractKeyArrayIndexProcedure<ResultBitBuffer> implements
        IParallelizableIndexProcedure<ResultBitBuffer> {

    /**
     * 
     */
    private static final long serialVersionUID = -5195874136364040815L;

    /**
     * Factory for {@link BatchContains} procedures.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     * @version $Id$
     */
    public static class BatchContainsConstructor extends AbstractKeyArrayIndexProcedureConstructor<BatchContains> {

        public static final BatchContainsConstructor INSTANCE = new BatchContainsConstructor(); 
        
        private BatchContainsConstructor() {
            
        }
        
        @Override
        public final boolean sendValues() {
            
            return false;
            
        }
 
        @Override
        public BatchContains newInstance(IRabaCoder keysCoder,
                IRabaCoder valsCoder, int fromIndex, int toIndex,
                byte[][] keys, byte[][] vals) {

            assert vals == null;
            
            return new BatchContains(keysCoder, fromIndex, toIndex, keys);
            
        }

    }

    /**
     * De-serialization ctor.
     *
     */
    public BatchContains() {
        
    }
    
    /**
     * Create a batch existence test operation.
     * 
     * @param keys
     *            A series of keys. Each key is an variable length unsigned
     *            byte[]. The keys MUST be presented in sorted order.
     * 
     * @see BatchContainsConstructor
     */
    protected BatchContains(final IRabaCoder keysCoder, final int fromIndex,
            final int toIndex, final byte[][] keys) {

        super(keysCoder, null, fromIndex, toIndex, keys, null/*vals*/);

    }

    @Override
    public final boolean isReadOnly() {
        
        return true;
        
    }
    
    /**
     * Applies the operation using {@link ISimpleBTree#contains(byte[])}.
     * 
     * @param ndx
     * 
     * @return A {@link ResultBitBuffer}.
     */
    @Override
    public ResultBitBuffer apply(final IIndex ndx) {

        final int n = getKeyCount();

        final boolean[] ret = new boolean[n];

        int i = 0, onCount = 0;

        while (i < n) {

            if(ret[i] = ndx.contains(getKey(i))) {
                
                onCount++;
                
            }

            i++;

        }

        return new ResultBitBuffer(n, ret, onCount);

    }
    
}
