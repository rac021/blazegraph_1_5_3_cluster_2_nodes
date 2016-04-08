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
 * Created on Apr 19, 2006
 */
package com.bigdata.cache;

import java.util.Iterator;
import java.util.NoSuchElementException;

import com.bigdata.cache.LRUCache.LRUIterator;

/**
 * Test suite for the LRU cache implementation.
 * 
 * @version $Id$
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 */

public class TestLRUCache extends AbstractCachePolicyTest {

    /**
     * 
     */
    public TestLRUCache() {
        super();
    }

    /**
     * @param name
     */
    public TestLRUCache(String name) {
        super(name);
    }

    /**
     * Constructor tests.
     */
    public void test_ctor()
    {
        
        new LRUCache(100);

        new LRUCache(100,0.9f);
        
        try {
            new LRUCache(0);
            fail("Expecting: "+IllegalArgumentException.class);
        }
        catch(IllegalArgumentException ex ) {
            log.info("Ingoring expected exception: "+ex);
        }

        try {
            new LRUCache(-1);
            fail("Expecting: "+IllegalArgumentException.class);
        }
        catch(IllegalArgumentException ex ) {
            log.info("Ingoring expected exception: "+ex);
        }
        
        try {
            new LRUCache(100,0.0f);
            fail("Expecting: "+IllegalArgumentException.class);
        }
        catch(IllegalArgumentException ex ) {
            log.info("Ingoring expected exception: "+ex);
        }
        
        try {
            new LRUCache(100,-1.0f);
            fail("Expecting: "+IllegalArgumentException.class);
        }
        catch(IllegalArgumentException ex ) {
            log.info("Ingoring expected exception: "+ex);
        }
        
    }

    /**
	 * Test fixture factory.
	 * 
	 * @return A new {@link LRUCache} with the stated capacity.
	 */
    public ICachePolicy getCachePolicy(int capacity ) {
    	return new LRUCache<Long,String>( capacity );
    }

    /**
     * Test verifies that the entry is correctly removed and that the traversal
     * order is correct when {@link LRUCache#iterator()}is used to remove cache
     * entries during traversal.
     */
    public void test_iterator_removal()
    {

        final int CAPACITY = 4;
        LRUCache<Long,String> cache = new LRUCache<Long,String>( CAPACITY );

        long[] oid = new long[] {
          1, 2, 3, 4, 5      
        };
        
        String[] obj = new String[] {
                "o1",
                "o2",
                "o3",
                "o4",
                "o5"
        };

        /*
         * Note: The cache order and the iterator order are from the Least
         * Recently Used to the Most Recently Used. This means that the last
         * element put() into the cache always shows up on the right hand edge
         * of the array used to test the cache ordering. When an element is
         * evicted from the cache it is always the element on the left hand edge
         * of that array.
         * 
         * LRU <- - - - - -> MRU
         */

        cache.put( oid[0], obj[0], true );
        cache.put( oid[1], obj[1], true );
        cache.put( oid[2], obj[2], true );
        cache.put( oid[3], obj[3], true );
        assertSameIterator("ordering",new Object[]{obj[0],obj[1],obj[2],obj[3]},cache.iterator() );

        // Iterator in LRU order.
        Iterator itr = cache.iterator();
        
        assertEquals( obj[0], itr.next() ); // LRU item.
        assertEquals( obj[1], itr.next() ); // next item.
        itr.remove();  // remove the 2nd item in LRU order.
        assertEquals("size", 3, cache.size() );
        assertEquals( obj[2], itr.next() ); // next item.
        assertEquals( obj[3], itr.next() ); // next item.
        assertFalse( itr.hasNext() ); // no more items.
        
        // Verify full ordering.
        assertSameIterator("ordering",new Object[]{obj[0],obj[2],obj[3]},cache.iterator() );

    }

    /**
     * Verifies that the cache correctly maintains the _dirty flag. This test
     * uses the {@link ICachePolicy#entryIterator()} to verify the _dirty flag,
     * so it also provides a check on the behavior of that iterator.
     */
    public void test_dirtyFlag() {

        final int CAPACITY = 4;
        LRUCache<Long,String> cache = new LRUCache<Long,String>( CAPACITY );

        long[] oid = new long[] {
          1, 2, 3, 4, 5      
        };
        
        String[] obj = new String[] {
                "o1",
                "o2",
                "o3",
                "o4",
                "o5"
        };

        boolean[] dirty = new boolean[] {
                false,
                false,
                true,
                false,
                true
        };

        /*
         * Note: The cache order and the iterator order are from the Least
         * Recently Used to the Most Recently Used. This means that the last
         * element put() into the cache always shows up on the right hand edge
         * of the array used to test the cache ordering. When an element is
         * evicted from the cache it is always the element on the left hand edge
         * of that array.
         * 
         * LRU <- - - - - -> MRU
         */

        cache.put( oid[0], obj[0], dirty[0] );
        cache.put( oid[1], obj[1], dirty[1] );
        cache.put( oid[2], obj[2], dirty[2] );
        cache.put( oid[3], obj[3], dirty[3] );
        
        // test entries in the cache.
        assertSameEntryOrdering("entry ordering",new ICacheEntry[]{
                new CacheEntry<Long,String>(oid[0],obj[0],dirty[0]),
                new CacheEntry<Long,String>(oid[1],obj[1],dirty[1]),
                new CacheEntry<Long,String>(oid[2],obj[2],dirty[2]),
                new CacheEntry<Long,String>(oid[3],obj[3],dirty[3]),
        	},
        	cache.entryIterator() );

        /*
         * Change the _dirty flag for an entry, which also updates the entry
         * order and verify the new ordering and the state of the _dirty flag.
         */
        dirty[1] = ! dirty[1];
        cache.put( oid[1], obj[1], dirty[1] );
        assertSameEntryOrdering("entry ordering",new ICacheEntry[]{
                new CacheEntry<Long,String>(oid[0],obj[0],dirty[0]),
                new CacheEntry<Long,String>(oid[2],obj[2],dirty[2]),
                new CacheEntry<Long,String>(oid[3],obj[3],dirty[3]),
                new CacheEntry<Long,String>(oid[1],obj[1],dirty[1]),
        	},
        	cache.entryIterator() );

        /*
         * Remove an entry and then reinsert it and make sure that the _dirty
         * flag state takes on the value that we specify.
         */
        dirty[1] = ! dirty[1];
        cache.remove( oid[1] );
        assertSameEntryOrdering("entry ordering",new ICacheEntry[]{
                new CacheEntry<Long,String>(oid[0],obj[0],dirty[0]),
                new CacheEntry<Long,String>(oid[2],obj[2],dirty[2]),
                new CacheEntry<Long,String>(oid[3],obj[3],dirty[3]),
        	},
        	cache.entryIterator() );
        cache.put( oid[1], obj[1], dirty[1] );
        assertSameEntryOrdering("entry ordering",new ICacheEntry[]{
                new CacheEntry<Long,String>(oid[0],obj[0],dirty[0]),
                new CacheEntry<Long,String>(oid[2],obj[2],dirty[2]),
                new CacheEntry<Long,String>(oid[3],obj[3],dirty[3]),
                new CacheEntry<Long,String>(oid[1],obj[1],dirty[1]),
        	},
        	cache.entryIterator() );

        // test clear().
        cache.clear();
        assertSameEntryOrdering("not empty", new ICacheEntry[] {}, cache
				.entryIterator());
        
    }
    
    /**
	 * Test verifies that put() may not be used to replace the object in the
	 * cache under a given oid, but only to update the dirty flag associated
	 * with that entry (and to update the LRU cache ordering).
	 */
    public void test_put_mayNotModifyObject() {
    	final String A = "A";
    	final String B = "B";
    	LRUCache<Long,String> cache = new LRUCache<Long,String>(5);

    	cache.put(0L,A,true);
        assertSameEntryOrdering("entry ordering",
				new ICacheEntry[] { new CacheEntry<Long,String>(0L, A, true), }, cache
						.entryIterator());
    	
        cache.put(0L,A,false);
        assertSameEntryOrdering("entry ordering",
				new ICacheEntry[] { new CacheEntry<Long,String>(0L, A, false), }, cache
						.entryIterator());

        try {
    		cache.put(0L,B,true);
    		fail("Expecting exception.");
    	}
    	catch(IllegalStateException ex) {
    		log.info("Ignoring expected exception: "+ex);
    	}
        assertSameEntryOrdering("entry ordering",
				new ICacheEntry[] { new CacheEntry<Long,String>(0L, A, false), }, cache
						.entryIterator());

    }
    
    /**
	 * Test verifies that objects put into the cache may be recovered using the
	 * appropriate key until the objects is evicted from the cache.
	 */
    public void test_get() {

        final int CAPACITY = 4;
        LRUCache<Long,String> cache = new LRUCache<Long,String>( CAPACITY );

        long[] oid = new long[] {
          1, 2, 3, 4, 5      
        };
        
        String[] obj = new String[] {
                "o1",
                "o2",
                "o3",
                "o4",
                "o5"
        };

        assertNull(cache.get(oid[0]));
        cache.put(oid[0], obj[0], true );
        assertEquals(obj[0],cache.get(oid[0]));

        cache.put(oid[1], obj[1], true );
        cache.put(oid[2], obj[2], true );
        cache.put(oid[3], obj[3], true );
        cache.put(oid[4], obj[4], true );
        assertNull(cache.get(oid[0]));

        cache.clear(); // note: this tests clear().
        assertEquals(0,cache.size());
        assertSameEntryOrdering("not empty", new ICacheEntry[] {}, cache
				.entryIterator());

        assertNull(cache.get(oid[0]));
        cache.put(oid[0], obj[0], true );
        cache.put(oid[1], obj[1], true );
        cache.put(oid[2], obj[2], true );
        assertEquals(obj[0],cache.get(oid[0]));
        cache.put(oid[3], obj[3], true );
        assertSameEntryOrdering("entry ordering",new ICacheEntry[]{
                new CacheEntry<Long,String>(oid[1],obj[1],true),
                new CacheEntry<Long,String>(oid[2],obj[2],true),
                new CacheEntry<Long,String>(oid[0],obj[0],true),
                new CacheEntry<Long,String>(oid[3],obj[3],true),
        	},
        	cache.entryIterator() );
        cache.put(oid[4], obj[4], true );
        assertSameEntryOrdering("entry ordering",new ICacheEntry[]{
                new CacheEntry<Long,String>(oid[2],obj[2],true),
                new CacheEntry<Long,String>(oid[0],obj[0],true),
                new CacheEntry<Long,String>(oid[3],obj[3],true),
                new CacheEntry<Long,String>(oid[4],obj[4],true),
        	},
        	cache.entryIterator() );
        assertNull(cache.get(oid[1]));
        assertEquals(obj[4],cache.get(oid[4]));
        assertEquals(obj[3],cache.get(oid[3]));
        assertEquals(obj[0],cache.get(oid[0]));
        assertEquals(obj[2],cache.get(oid[2]));
        
    }

    /**
	 * <p>
	 * This test models a situation in a cache eviction from the hard reference
	 * cache has a side effect that causes another object to enter the cache.
	 * The test verifies that the secondary cache eviction is correctly handled
	 * as a temporary over capacity condition (no secondary eviction is
	 * performed) and that the cache returns to capacity when the primary cache
	 * eviction has been completed. The net result is that both the LRU and the
	 * penultimate LRU objects are evicted from the cache and that the objects
	 * entering the cache enter in the MRU and penultimate MRU positions.
	 * </p>
	 * 
	 * @see LRUCache#put(long, Object, boolean)
	 */
    public void test_nextedCacheEvictionCausesTemporaryOverCapacity() {
    	
        final int CAPACITY = 4;
        LRUCache<Long,String> cache = new LRUCache<Long,String>( CAPACITY );

        long[] oid = new long[] {
          10, 11, 12, 13, 14, 15
        };
        
        String[] obj = new String[] {
                "o10",
                "o11",
                "o12",
                "o13",
                "o14",
                "o15"
        };

        /*
		 * Set our cache eviction listener to add the described entry into the
		 * cache when it receives a cache eviction event.
		 */
        MyCacheListenerAddsEntry<Long, String> l = new MyCacheListenerAddsEntry<Long, String>(
                cache, oid[5], obj[5], true);
        cache.setListener( l );
        l.denyEvents();
        
        /*
		 * Fill the cache to capacity and verify its state.
		 */
        cache.put(oid[0], obj[0], true );
        cache.put(oid[1], obj[1], true );
        cache.put(oid[2], obj[2], true );
        cache.put(oid[3], obj[3], true );
        assertSameEntryOrdering("entry ordering",new ICacheEntry[]{
                new CacheEntry<Long,String>(oid[0],obj[0],true),
                new CacheEntry<Long,String>(oid[1],obj[1],true),
                new CacheEntry<Long,String>(oid[2],obj[2],true),
                new CacheEntry<Long,String>(oid[3],obj[3],true),
        	},
        	cache.entryIterator() );
                
        /*
		 * Force a cache eviction event by adding another entry to the cache.
		 * 
		 * The expected sequence is:
		 *   
		 *   put( 4 )
		 *     objectEvicted( 0 ) - purge LRU from cache.
		 *        put( 5 ) - does not cause eviction in nested put(), so cache is over capacity.
		 *     objectEvicted( 1 ) - purge LRU from cache (now at one under capacity)
		 * 
		 * Since objects do not enter the cache until after the objectEvicted event
		 * has been served, this means that 4 is in the MRU position and 5 is in the
		 * penultimate MRU position since the put() for 4 completes _after_ the nested
		 * put() for 5.  This is reflected in the cache order test below.
		 */
        l.addExpectedEvent(oid[0], obj[0], true);
        l.addExpectedEvent(oid[1], obj[1], true);
        cache.put(oid[4], obj[4], true );
        l.denyEvents();
        
        /*
		 * Verify the state of the cache afterwards. The cache should be back at
		 * capacity after a brief over capacity while handling the nested put()
		 * of an object not in the cache.
		 */
        showCache( cache );
        assertSameEntryOrdering("entry ordering",new ICacheEntry[]{
                new CacheEntry<Long,String>(oid[2],obj[2],true), // LRU position.
                new CacheEntry<Long,String>(oid[3],obj[3],true),
                new CacheEntry<Long,String>(oid[5],obj[5],true), 
                new CacheEntry<Long,String>(oid[4],obj[4],true), // MRU position
        	},
        	cache.entryIterator() );
        
    }

    /**
	 * This tests concurrent modification of the LRU ordering during traveral.
	 * 
	 * @see LRUIterator
	 */
    public void test_concurrentModificationDuringTraveral() {

        final int CAPACITY = 4;
        LRUCache<Long,String> cache = new LRUCache<Long,String>( CAPACITY );

        long[] oid = new long[] {
          0, 1, 2, 3, 4      
        };
        
        String[] obj = new String[] {
                "o0",
                "o1",
                "o2",
                "o3",
                "o4"
        };

        /*
		 * Set our cache eviction listener to add the described entry into the
		 * cache when it receives a cache eviction event.
		 */
        MyCacheListener<Long,String> l = new MyCacheListener<Long,String>();
        cache.setListener( l );
        l.denyEvents();
        
        /*
		 * Fill the cache to capacity and verify its state.
		 */
        cache.clear();
        cache.put(oid[0], obj[0], true );
        cache.put(oid[1], obj[1], true );
        cache.put(oid[2], obj[2], true );
        cache.put(oid[3], obj[3], true );
        assertSameEntryOrdering("entry ordering",new ICacheEntry[]{
                new CacheEntry<Long,String>(oid[0],obj[0],true),
                new CacheEntry<Long,String>(oid[1],obj[1],true),
                new CacheEntry<Long,String>(oid[2],obj[2],true),
                new CacheEntry<Long,String>(oid[3],obj[3],true),
        	},
        	cache.entryIterator() );

        /*
         * Verify state under one at a time iteration.
         */
        Iterator itr = cache.entryIterator();
        assertSameEntry("LRU[0]", new CacheEntry<Long,String>(oid[0],obj[0],true), (ICacheEntry)itr.next() );
        assertSameEntry("LRU[1]", new CacheEntry<Long,String>(oid[1],obj[1],true), (ICacheEntry)itr.next() );
        assertSameEntry("LRU[2]", new CacheEntry<Long,String>(oid[2],obj[2],true), (ICacheEntry)itr.next() );
        assertSameEntry("LRU[3]", new CacheEntry<Long,String>(oid[3],obj[3],true), (ICacheEntry)itr.next() );
        assertFalse( itr.hasNext() );
        try {
        	itr.next();
        	fail("Expecting: "+NoSuchElementException.class);
        }
        catch(NoSuchElementException ex) {
        	log.info("Ignoring expected exception: "+ex);
        }
        
        /*
		 * Verify state under one at a time iteration with concurrent
		 * modification.
		 * 
		 * This removes the first entry before we visit it.
		 */
        itr = cache.entryIterator();
        cache.remove(oid[0]);
//        assertSameEntry("LRU[0]", new CacheEntry<Long,String>(oid[0],obj[0],true), (ICacheEntry)itr.next() );
        assertSameEntry("LRU[1]", new CacheEntry<Long,String>(oid[1],obj[1],true), (ICacheEntry)itr.next() );
        assertSameEntry("LRU[2]", new CacheEntry<Long,String>(oid[2],obj[2],true), (ICacheEntry)itr.next() );
        assertSameEntry("LRU[3]", new CacheEntry<Long,String>(oid[3],obj[3],true), (ICacheEntry)itr.next() );
        assertFalse( itr.hasNext() );
        try {
        	itr.next();
        	fail("Expecting: "+NoSuchElementException.class);
        }
        catch(NoSuchElementException ex) {
        	log.info("Ignoring expected exception: "+ex);
        }

        /*
		 * Fill the cache to capacity and verify its state.
		 */
        cache.clear();
        cache.put(oid[0], obj[0], true );
        cache.put(oid[1], obj[1], true );
        cache.put(oid[2], obj[2], true );
        cache.put(oid[3], obj[3], true );
        assertSameEntryOrdering("entry ordering",new ICacheEntry[]{
                new CacheEntry<Long,String>(oid[0],obj[0],true),
                new CacheEntry<Long,String>(oid[1],obj[1],true),
                new CacheEntry<Long,String>(oid[2],obj[2],true),
                new CacheEntry<Long,String>(oid[3],obj[3],true),
        	},
        	cache.entryIterator() );

        /*
		 * Verify state under one at a time iteration with concurrent
		 * modification.
		 * 
		 * This removes the 2nd entry before we would visit it.
		 */
        itr = cache.entryIterator();
        assertSameEntry("LRU[0]", new CacheEntry<Long,String>(oid[0],obj[0],true), (ICacheEntry)itr.next() );
        cache.remove(oid[1]);
//        assertSameEntry("LRU[1]", new CacheEntry<Long,String>(oid[1],obj[1],true), (ICacheEntry)itr.next() );
        assertSameEntry("LRU[2]", new CacheEntry<Long,String>(oid[2],obj[2],true), (ICacheEntry)itr.next() );
        assertSameEntry("LRU[3]", new CacheEntry<Long,String>(oid[3],obj[3],true), (ICacheEntry)itr.next() );
        assertFalse( itr.hasNext() );
        try {
        	itr.next();
        	fail("Expecting: "+NoSuchElementException.class);
        }
        catch(NoSuchElementException ex) {
        	log.info("Ignoring expected exception: "+ex);
        }
        
        /*
		 * Fill the cache to capacity and verify its state.
		 */
        cache.clear();
        cache.put(oid[0], obj[0], true );
        cache.put(oid[1], obj[1], true );
        cache.put(oid[2], obj[2], true );
        cache.put(oid[3], obj[3], true );
        assertSameEntryOrdering("entry ordering",new ICacheEntry[]{
                new CacheEntry<Long,String>(oid[0],obj[0],true),
                new CacheEntry<Long,String>(oid[1],obj[1],true),
                new CacheEntry<Long,String>(oid[2],obj[2],true),
                new CacheEntry<Long,String>(oid[3],obj[3],true),
        	},
        	cache.entryIterator() );

        /*
		 * Verify state under one at a time iteration with concurrent
		 * modification.
		 * 
		 * This removes the last entry before we would visit it.
		 */
        itr = cache.entryIterator();
        assertSameEntry("LRU[0]", new CacheEntry<Long,String>(oid[0],obj[0],true), (ICacheEntry)itr.next() );
        assertSameEntry("LRU[1]", new CacheEntry<Long,String>(oid[1],obj[1],true), (ICacheEntry)itr.next() );
        assertSameEntry("LRU[2]", new CacheEntry<Long,String>(oid[2],obj[2],true), (ICacheEntry)itr.next() );
        cache.remove(oid[3]);
//        assertSameEntry("LRU[3]", new CacheEntry<Long,String>(oid[3],obj[3],true), (ICacheEntry)itr.next() );
        assertFalse( itr.hasNext() );
        try {
        	itr.next();
        	fail("Expecting: "+NoSuchElementException.class);
        }
        catch(NoSuchElementException ex) {
        	log.info("Ignoring expected exception: "+ex);
        }
                
    }
    
    /**
     * Test helper verifies the expected state of a cache entry.
     * 
     * @param msg
     * @param expected
     * @param actual
     */
    static void assertSameEntry(String msg, ICacheEntry expected, ICacheEntry actual ) {
    	assertEquals(msg+": oid", expected.getKey(), actual.getKey() );
    	assertEquals(msg+": value", expected.getObject(), actual.getObject() );
    	assertEquals(msg+": dirty", expected.isDirty(), actual.isDirty() );
    }
    
    /**
	 * Implementation adds an entry to the cache when it receives a cache
	 * eviction notice. The cache entry to be added is described by the
	 * parameters to the constructor. This is a one-time behavior. The listener
	 * will log subsequent events but otherwise take no action.
	 * 
	 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
	 * @version $Id$
	 */
    public static class MyCacheListenerAddsEntry<K,T> extends MyCacheListener<K,T>
    {
    	private final ICachePolicy<K,T> _cache;
    	private final K _oid;
    	private final T _obj;
    	private final boolean _dirty;
    	private int _nevents = 0;
    	
    	public MyCacheListenerAddsEntry( ICachePolicy<K,T> cache, K oid, T obj, boolean dirty ) {
    		super();
    		this._cache = cache;
    		this._oid = oid;
    		this._obj = obj;
    		this._dirty = dirty;
    	}
    	
		public void objectEvicted(ICacheEntry<K,T> entry) {
			super.objectEvicted(entry);
			_nevents++;
	    	showCache(_cache);
			System.err.println("objectEvicted("+entry+"), _nevents="+_nevents);
			if( _nevents == 1 ) {
				denyEvents(); // not expecting an eviction.
				_cache.put(_oid, _obj, _dirty);
				allowEvents(); // allow next expected eviction.
			}
		}
		
    }
    
}
