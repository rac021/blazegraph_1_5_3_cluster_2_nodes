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
 * Created on Jan 7, 2008
 */

package com.bigdata.btree.proc;

import it.unimi.dsi.bits.BitVector;
import it.unimi.dsi.bits.LongArrayBitVector;
import it.unimi.dsi.io.InputBitStream;
import it.unimi.dsi.io.OutputBitStream;

import java.io.Externalizable;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.bigdata.btree.Errors;
import com.bigdata.btree.ITupleSerializer;
import com.bigdata.btree.raba.IRaba;
import com.bigdata.btree.raba.ReadOnlyKeysRaba;
import com.bigdata.btree.raba.ReadOnlyValuesRaba;
import com.bigdata.btree.raba.codec.IRabaCoder;
import com.bigdata.io.AbstractFixedByteArrayBuffer;
import com.bigdata.io.DataOutputBuffer;
import com.bigdata.io.FixedByteArrayBuffer;
import com.bigdata.service.Split;

/**
 * Abstract base class supports compact serialization and compression for remote
 * {@link IKeyArrayIndexProcedure} execution (procedures may be executed on a
 * local index, but they are only (de-)serialized when executed on a remote
 * index).
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 */
// * <pre>
// * @param R
// *            The data type of the <em>R</em>esult obtained by applying the
// *            procedure to a local index or local index view. Instances of this
// *            interface are logically "mapped" across one or more index
// *            partitions, with one <em>R</em>esult obtained per index
// *            partition.
// * 
// * @param H
// *            The data type of the {@link IResultHandler} operation that is
// *            applied to the <em>R</em>esult obtained for each index
// *            partition.
// * 
// * @param A
// *            The data type of the <em>A</em>ggregated <em>R</em>esults.
// * 
// *    &lt;R, H extends IResultHandler&lt;R, A&gt;, A&gt;
// * </pre>
abstract public class AbstractKeyArrayIndexProcedure<T> extends
        AbstractIndexProcedure<T> implements IKeyArrayIndexProcedure<T>,
        Externalizable {

//    private static final Logger log = Logger.getLogger(AbstractKeyArrayIndexProcedure.class);
//    
//    /**
//     * True iff the {@link #log} level is INFO or less.
//     */
//    final protected boolean INFO = log.getEffectiveLevel().toInt() <= Level.INFO
//            .toInt();
//
//    /**
//     * True iff the {@link #log} level is DEBUG or less.
//     */
//    final protected boolean DEBUG = log.isDebugEnabled();

    /**
     * The object used to (de-)code the keys when they are sent to the remote
     * service.
     */
    private IRabaCoder keysCoder;

    /**
     * The object used to (de-)code the values when they are sent to the remote
     * service.
     */
    private IRabaCoder valsCoder;
    
//    /**
//     * Index of the first element to be used in {@link #keys} and {@link #vals}
//     * and serialized as <code>0</code>. This makes it possible to reuse the
//     * original keys[] and vals[] when the procedure is mapped across a
//     * key-range partitioned index while only sending the minimum amount of data
//     * when the procedure is serialized.
//     */
//    private int fromIndex;
//    
//    /**
//     * Index of the first element to NOT be used in {@link #keys} and
//     * {@link #vals} and serialized as <code>(toIndex - fromIndex)</code>.
//     * This makes it possible to reuse the original keys[] and vals[] when the
//     * procedure is mapped across a key-range partitioned index while only
//     * sending the minimum amount of data when the procedure is serialized.
//     */
//    private int toIndex;

    /**
     * The keys.
     */
    private IRaba keys;

    /**
     * The values.
     */
    private IRaba vals;

//    /**
//     * Index of the first element to be used in {@link #keys} and {@link #vals}
//     * and serialized as <code>0</code>. This makes it possible to reuse the
//     * original keys[] and vals[] when the procedure is mapped across a
//     * key-range partitioned index while only sending the minimum amount of data
//     * when the procedure is serialized.
//     */
//    final public int getFromIndex() {
//
//        return fromIndex;
//
//    }
//
//    /**
//     * Index of the first element to NOT be used in {@link #keys} and
//     * {@link #vals} and serialized as <code>(toIndex - fromIndex)</code>.
//     * This makes it possible to reuse the original keys[] and vals[] when the
//     * procedure is mapped across a key-range partitioned index while only
//     * sending the minimum amount of data when the procedure is serialized.
//     */
//    final public int getToIndex() {
//
//        return toIndex;
//        
//    }
    
    @Override
    final public IRaba getKeys() {
        
        return keys;
        
    }
    
    @Override
    final public IRaba getValues() {
        
        return vals;
        
    }
    
    @Override
    final public int getKeyCount() {

        return keys.size();

    }

    @Override
    final public byte[] getKey(final int i) {

        return keys.get(i);

    }

    @Override
    final public byte[] getValue(final int i) {

        if (vals == null)
            throw new UnsupportedOperationException();
        
        return vals.get( i );

    }

    /**
     * De-serialization constructor.
     */
    protected AbstractKeyArrayIndexProcedure() {

    }

    /**
     * @param keySer
     *            The object used to serialize the <i>keys</i>.
     * @param valSer
     *            The object used to serialize the <i>vals</i> (optional IFF
     *            <i>vals</i> is <code>null</code>).
     * @param fromIndex
     *            The index of the first key in <i>keys</i> to be processed
     *            (inclusive).
     * @param toIndex
     *            The index of the last key in <i>keys</i> to be processed.
     * @param keys
     *            The keys (<em>unsigned</em> variable length byte[]s) MUST
     *            be in sorted order (the logic to split procedures across
     *            partitioned indices depends on this, plus ordered reads and
     *            writes on indices are MUCH more efficient).
     * @param vals
     *            The values (optional, must be co-indexed with <i>keys</i>
     *            when non-<code>null</code>).
     */
    protected AbstractKeyArrayIndexProcedure(final IRabaCoder keysCoder,
            final IRabaCoder valsCoder, final int fromIndex, final int toIndex,
            final byte[][] keys, final byte[][] vals) {

        if (keysCoder == null)
            throw new IllegalArgumentException();
        
        if (valsCoder == null && vals != null)
            throw new IllegalArgumentException();
        
        if (keys == null)
            throw new IllegalArgumentException(Errors.ERR_KEYS_NULL);

        if (fromIndex < 0)
            throw new IllegalArgumentException(Errors.ERR_FROM_INDEX);

        if (fromIndex >= toIndex )
            throw new IllegalArgumentException(Errors.ERR_FROM_INDEX);

        if (toIndex > keys.length )
            throw new IllegalArgumentException(Errors.ERR_TO_INDEX);

        if (vals != null && toIndex > vals.length)
            throw new IllegalArgumentException(Errors.ERR_TO_INDEX);

        this.keysCoder = keysCoder;
        
        this.valsCoder = valsCoder;
        
//        this.fromIndex = fromIndex;
//        
//        this.toIndex = toIndex;

        this.keys = new ReadOnlyKeysRaba(fromIndex, toIndex, keys);

        this.vals = (vals == null ? null : new ReadOnlyValuesRaba(fromIndex,
                toIndex, vals));

    }

//    /**
//     * Return the object used to (de-)code the keys when they are sent to the
//     * remote service.
//     */
//    final protected IRabaCoder getKeysCoder() {
//
//        return keysCoder;
//
//    }
//
//    /**
//     * Return the object used to (de-)code the values when they are sent to the
//     * remote service.
//     */
//    final protected IRabaCoder getValuesCoder() {
//        
//        return valsCoder;
//        
//    }
    
    @Override
    final public void readExternal(final ObjectInput in) throws IOException,
            ClassNotFoundException {

        readMetadata(in);

        final boolean haveVals = in.readBoolean();

//        final int n = toIndex - fromIndex;

        {
            
            // the keys.
            
            final int len = in.readInt();
            
            final byte[] a = new byte[len];
            
            in.readFully(a);
            
            keys = keysCoder.decode(FixedByteArrayBuffer.wrap(a));
            
//            keys = new MutableValuesRaba(0, 0, new byte[n][]);
//
//            getKeysCoder().read(in, keys);
            
        }

        if(haveVals) {
        
            /*
             * Wrap the coded the values.
             */
            
            // the byte length of the coded values.
            final int len = in.readInt();
            
            // allocate backing array.
            final byte[] a = new byte[len];
            
            // read the coded record into the array.
            in.readFully(a);
            
            // wrap the coded record.
            vals = valsCoder.decode(FixedByteArrayBuffer.wrap(a));
            
//            vals = new MutableValuesRaba( 0, 0, new byte[n][] );
//        
//            getValuesCoder().read(in, vals);
            
        } else {
            
            vals = null;
            
        }
        
    }

    @Override
    final public void writeExternal(final ObjectOutput out) throws IOException {

        writeMetadata(out);

        out.writeBoolean(vals != null); // haveVals

        final DataOutputBuffer buf = new DataOutputBuffer();
        {

            // code the keys
            final AbstractFixedByteArrayBuffer slice = keysCoder.encode(keys,
                    buf);

            // The #of bytes in the coded keys.
            out.writeInt(slice.len());

            // The coded keys.
            slice.writeOn(out);

        }

        if (vals != null) {

            // reuse the buffer.
            buf.reset();
            
            // code the values.
            final AbstractFixedByteArrayBuffer slice = valsCoder.encode(vals,
                    buf);

            // The #of bytes in the coded keys.
            out.writeInt(slice.len());

            // The coded keys.
            slice.writeOn(out);

        }

    }

    /**
     * Reads metadata written by {@link #writeMetadata(ObjectOutput)}.
     * 
     * @param in
     * 
     * @throws IOException
     * @throws ClassNotFoundException
     */
    protected void readMetadata(final ObjectInput in) throws IOException,
            ClassNotFoundException {

        final byte version = in.readByte();

        switch (version) {
        case VERSION0:
            break;
        default:
            throw new IOException();
        }
        
//        fromIndex = 0;
//
//        toIndex = (int) LongPacker.unpackLong(in);

        keysCoder = (IRabaCoder) in.readObject();

        valsCoder = (IRabaCoder) in.readObject();

    }

    /**
     * Writes metadata (not the keys or values, but just other metadata used by
     * the procedure).
     * <p>
     * The default implementation writes out the {@link #getKeysCoder()} and the
     * {@link #getValuesCoder()}.
     * 
     * @param out
     * 
     * @throws IOException
     */
    protected void writeMetadata(final ObjectOutput out) throws IOException {

        out.write(VERSION0);
        
//        final int n = toIndex - fromIndex;
//        
//        LongPacker.packLong(out, n);
        
        out.writeObject(keysCoder);

        out.writeObject(valsCoder);
        
    }
    
    private static final byte VERSION0 = 0x00;

    /**
     * A class useful for sending some kinds of data back from a remote
     * procedure call (those readily expressed as a <code>byte[][]</code>).
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     */
    public static class ResultBuffer implements Externalizable {
        
        /**
         * 
         */
        private static final long serialVersionUID = 3545214696708412869L;

        private IRaba vals;

        private IRabaCoder valsCoder;
        
        /**
         * De-serialization ctor.
         *
         */
        public ResultBuffer() {
            
        }
        
        /**
         * 
         * @param n
         *            #of values in <i>a</i> containing data.
         * @param a
         *            The data.
         * @param valSer
         *            The data are serialized using using this object. Typically
         *            this is the value returned by
         *            {@link ITupleSerializer#getLeafValuesCoder()}.
         */
        public ResultBuffer(final int n, final byte[][] a,
                final IRabaCoder valsCoder) {

            assert n >= 0;
            assert a != null;
            assert valsCoder != null;
                        
            this.vals = new ReadOnlyValuesRaba(0/* fromIndex */, n/* toIndex */, a);
            
            this.valsCoder = valsCoder;
            
        }
        
        public IRaba getValues() {
            
            return vals;
            
        }
        
        /**
         * @deprecated by {@link #getValues()}
         */
        public int getResultCount() {
            
            return vals.size();
            
        }
        
        /**
         * @deprecated by {@link #getValues()}
         */
        public byte[] getResult(final int index) {

            return vals.get(index);

        }

        @Override
        public void readExternal(final ObjectInput in) throws IOException,
                ClassNotFoundException {

            final byte version = in.readByte();
            
            switch (version) {
            case VERSION0:
                break;
            default:
                throw new IOException();
            }

//            final int n = in.readInt();

            // The values coder.
            valsCoder = (IRabaCoder) in.readObject();

            // The #of bytes in the coded values.
            final int len = in.readInt();
            
            final byte[] b = new byte[len];
            
            in.readFully(b);
            
            // Wrap the coded values.
            vals = valsCoder.decode(FixedByteArrayBuffer.wrap(b));

//            a = new MutableValuesRaba(0/* fromIndex */, 0/* toIndex */,
//                    n/* capacity */, new byte[n][]);
//
//            valSer.read(in, a);
            
        }

        @Override
        public void writeExternal(final ObjectOutput out) throws IOException {

            out.writeByte(VERSION0);
            
//            out.writeInt(a.size());

            // The values coder.
            out.writeObject(valsCoder);
            
            // Code the values.
            final AbstractFixedByteArrayBuffer slice = valsCoder.encode(vals,
                    new DataOutputBuffer());
            
            // The #of bytes in the coded keys.
            out.writeInt(slice.len());

            // The coded keys.
            slice.writeOn(out);
            
//            valSer.write(out, a);

        }
        
        private static final byte VERSION0 = 0x00;
        
    }

    /**
     * A class useful for sending a logical <code>boolean[]</code> back from a
     * remote procedure call.
     * 
     * @todo provide run-length coding for bits?
     * 
     * @todo use {@link LongArrayBitVector} for more compact storage?
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan
     *         Thompson</a>
     */
    public static class ResultBitBuffer implements Externalizable {

        /**
         * 
         */
        private static final long serialVersionUID = 1918403771057371471L;

        private int n;

        /**
         * @todo represent using a {@link BitVector}. {@link LongArrayBitVector}
         *       when allocating. Directly write the long[] backing bits
         *       (getBits()) onto the output stream. Reconstruct from backing
         *       long[] when reading. Hide the boolean[] from the API by
         *       modifying {@link #getResult()} to accept the index of the bit
         *       of interest or to return the {@link BitVector} directly.
         */
//        private BitVector a;
        private boolean[] a;

        transient private int onCount;
        
        /**
         * De-serialization ctor.
         */
        public ResultBitBuffer() {
            
        }

        /**
         * 
         * @param n
         *            #of values in <i>a</i> containing data.
         * @param a
         *            The data.
         * @param onCount
         *            The #of bits which were on in the array.
         */
        public ResultBitBuffer(final int n, final boolean[] a, final int onCount) {

            if (n < 0)
                throw new IllegalArgumentException();
            
            if (a == null)
                throw new IllegalArgumentException();
            
            if (onCount < 0 || onCount > n)
                throw new IllegalArgumentException();
            
            this.n = n;

            this.a = a;

            /*
             * Note: The onCount is a require parameter because this class is
             * used in non-RMI contexts as well where it is not deserialized and
             * hence onCount will not be set unless it is done in this
             * constructor.
             */
 
            this.onCount = onCount;

        }

        public int getResultCount() {
            
            return n;
            
        }

        /**
         * 
         */
        public boolean[] getResult() {

            return a;

        }
        
        /**
         * Return the #of bits which are "on" (aka true).
         */
        public int getOnCount() {
            
            return onCount;
            
        }

        @Override
        public void readExternal(final ObjectInput in) throws IOException,
                ClassNotFoundException {

            final byte version = in.readByte();

            switch (version) {
            case VERSION0:
                break;
            default:
                throw new UnsupportedOperationException("Unknown version: "
                        + version);
            }

            @SuppressWarnings("resource")
            final InputBitStream ibs = new InputBitStream((InputStream) in,
                    0/* unbuffered */, false/* reflectionTest */);

            n = ibs.readNibble();

//            a = LongArrayBitVector.getInstance(n);
            a = new boolean[n];

            for (int i = 0; i < n; i++) {

                final boolean bit = ibs.readBit() == 1 ? true : false;
//                a.set(i, bit);

                if (a[i] = bit)
                    onCount++;
                
            }
            
        }

        @Override
        public void writeExternal(final ObjectOutput out) throws IOException {

            out.writeByte(VERSION);
            
            @SuppressWarnings("resource")
            final OutputBitStream obs = new OutputBitStream((OutputStream) out,
                    0/* unbuffered! */, false/*reflectionTest*/);

            obs.writeNibble(n);
            
//            obs.write(a.iterator());

            for (int i = 0; i < n; i++) {

                obs.writeBit(a[i]);

            }

            obs.flush();
            
        }

        /**
         * The initial version.
         */
        private static final transient byte VERSION0 = 0;

        /**
         * The current version.
         */
        private static final transient byte VERSION = VERSION0;

    }

    /**
     * Knows how to aggregate {@link ResultBuffer} objects.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     */
    public static class ResultBufferHandler implements
            IResultHandler<ResultBuffer, ResultBuffer> {

        private final byte[][] results;
        private final IRabaCoder valsCoder;

        public ResultBufferHandler(final int nkeys, final IRabaCoder valsCoder) {

            this.results = new byte[nkeys][];

            this.valsCoder = valsCoder;
            
        }

        @Override
        public void aggregate(final ResultBuffer result, final Split split) {

            final IRaba src = result.getValues();
            
            for (int i = 0, j = split.fromIndex; i < split.ntuples; i++, j++) {

                results[j] = src.get(i);
                
            }
            
        }

        /**
         * The aggregated results.
         * 
         * FIXME It would be better to wrap the results from each split and
         * index into them directly in order to avoid decoding the byte[][]s
         * associated with each split. We would need to return an appropriate
         * {@link IRaba} implementation here instead.
         */
        @Override
        public ResultBuffer getResult() {

            return new ResultBuffer(results.length, results, valsCoder);

        }

    }

    /**
     * Knows how to aggregate {@link ResultBitBuffer} objects.
     */
    public static class ResultBitBufferHandler implements
            IResultHandler<ResultBitBuffer, ResultBitBuffer> {

        private final boolean[] results;
        
        /**
         * I added this so I could encode information about tuple modification
         * that takes more than one boolean to encode.  For example, SPOs can
         * be: INSERTED, REMOVED, UPDATED, NO_OP (2 bits).
         */
        private final int multiplier;
        
        private final AtomicInteger onCount = new AtomicInteger();

        public ResultBitBufferHandler(final int nkeys) {
            
            this(nkeys, 1);
            
        }
        
        public ResultBitBufferHandler(final int nkeys, final int multiplier) {

            results = new boolean[nkeys*multiplier];
            this.multiplier = multiplier;

        }

        @Override
        public void aggregate(final ResultBitBuffer result, final Split split) {

            System.arraycopy(result.getResult(), 0, results, 
                    split.fromIndex*multiplier,
                    split.ntuples*multiplier);
            
            onCount.addAndGet(result.getOnCount());

        }

        /**
         * The aggregated results.
         */
        @Override
        public ResultBitBuffer getResult() {

            return new ResultBitBuffer(results.length, results, onCount.get());

        }

    }

    /**
     * Counts the #of <code>true</code> bits in the {@link ResultBitBuffer}(s).
     */
    public static class ResultBitBufferCounter implements
            IResultHandler<ResultBitBuffer, Long> {

        private final AtomicLong ntrue = new AtomicLong();

        public ResultBitBufferCounter() {

        }

        @Override
        public void aggregate(final ResultBitBuffer result, final Split split) {

            int delta = 0;

            for (int i = 0; i < result.n; i++) {

                if (result.a[i])
                    delta++;

            }

            this.ntrue.addAndGet(delta);

        }

        /**
         * The #of <code>true</code> values observed in the aggregated
         * {@link ResultBitBuffer}s.
         */
        @Override
        public Long getResult() {

            return ntrue.get();

        }

    }

}
