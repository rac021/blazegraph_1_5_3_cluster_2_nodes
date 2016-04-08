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
package com.bigdata.journal.jini.ha;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.UUID;

import com.bigdata.btree.BTree;
import com.bigdata.btree.BytesUtil;
import com.bigdata.btree.DefaultTupleSerializer;
import com.bigdata.btree.ITuple;
import com.bigdata.btree.IndexMetadata;
import com.bigdata.btree.keys.ASCIIKeyBuilderFactory;
import com.bigdata.btree.keys.IKeyBuilderFactory;
import com.bigdata.btree.keys.KeyBuilder;
import com.bigdata.journal.AbstractCommitTimeIndex;
import com.bigdata.journal.ICommitTimeEntry;
import com.bigdata.journal.IRootBlockView;
import com.bigdata.journal.RootBlockView;
import com.bigdata.journal.jini.ha.SnapshotIndex.ISnapshotRecord;
import com.bigdata.rawstore.Bytes;
import com.bigdata.util.ChecksumUtility;

/**
 * {@link BTree} mapping <em>commitTime</em> (long integers) to
 * {@link ISnapshotRecord} records.
 * <p>
 * This object is thread-safe for concurrent readers and writers.
 * <p>
 * Note: This is used as a transient data structure that is populated from the
 * file system by the {@link HAJournalServer}. 
 */
public class SnapshotIndex extends AbstractCommitTimeIndex<ISnapshotRecord> {
    
//    /**
//     * The underlying index. Access to this is NOT thread safe unless you take
//     * the appropriate lock on the {@link #readWriteLock}.
//     */
//    private final BTree btree;

    /**
     * Create a transient instance.
     * 
     * @return The new instance.
     */
    static public SnapshotIndex createTransient() {
    
        final IndexMetadata metadata = new IndexMetadata(UUID.randomUUID());
        
//        metadata.setBTreeClassName(SnapshotIndex.class.getName());

        metadata.setTupleSerializer(new TupleSerializer(
                new ASCIIKeyBuilderFactory(Bytes.SIZEOF_LONG)));

        final BTree ndx = BTree.createTransient(/*store, */metadata);
        
        return new SnapshotIndex(ndx);
        
    }

    private SnapshotIndex(final BTree ndx) {

        // Wrap B+Tree for read/write thread safety.
        super(ndx);
        
//        this.btree = ndx;
//        
////        this.delegate = new UnisolatedReadWriteIndex(ndx);
//        
//        // Save reference to lock for extended synchronization patterns.
//        this.readWriteLock = UnisolatedReadWriteIndex.getReadWriteLock(ndx);
        
    }
    
//    /**
//     * Load from the store.
//     * 
//     * @param store
//     *            The backing store.
//     * @param checkpoint
//     *            The {@link Checkpoint} record.
//     * @param metadata
//     *            The metadata record for the index.
//     */
//    public SnapshotIndex(final IRawStore store, final Checkpoint checkpoint,
//            final IndexMetadata metadata, final boolean readOnly) {
//
//        super(store, checkpoint, metadata, readOnly);
//
//    }
    
    /**
     * Interface for access to the snapshot metadata.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     */
    public static interface ISnapshotRecord extends ICommitTimeEntry{
    
//        /**
//         * Return the bytes on the disk for the snapshot file.
//         */
//        public long sizeOnDisk();
//        
//        /**
//         * Return the {@link IRootBlockView} of the snapshot.
//         */
//        public IRootBlockView getRootBlock();
        
    }
    
    public static class SnapshotRecord implements ISnapshotRecord,
            Externalizable {

        private static final int VERSION0 = 0x0;

        private static final int currentVersion = VERSION0;
        
        /**
         * Note: This is NOT {@link Serializable}.
         */
        private IRootBlockView rootBlock;

        private long sizeOnDisk;

        /**
         * De-serialization constructor.
         */
        public SnapshotRecord() {
        }
        
        public SnapshotRecord(final IRootBlockView rootBlock,
                final long sizeOnDisk) {

            if (rootBlock == null)
                throw new IllegalArgumentException();

            if (sizeOnDisk < 0L)
                throw new IllegalArgumentException();

            this.rootBlock = rootBlock;

            this.sizeOnDisk = sizeOnDisk;

        }
        
        @Override
        public long sizeOnDisk() {
            return sizeOnDisk;
        }

        @Override
        public IRootBlockView getRootBlock() {
            return rootBlock;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o)
                return true;
            if (!(o instanceof ISnapshotRecord))
                return false;
            final ISnapshotRecord t = (ISnapshotRecord) o;
            if (sizeOnDisk() != t.sizeOnDisk())
                return false;
            if (!getRootBlock().equals(t.getRootBlock()))
                return false;
            return true;
        }

        @Override
        public int hashCode() {
            return getRootBlock().hashCode();
        }

        @Override
        public void writeExternal(final ObjectOutput out) throws IOException {

            out.writeInt(currentVersion);

            final byte[] a = BytesUtil.getBytes(rootBlock.asReadOnlyBuffer());

            final int sizeOfRootBlock = a.length;

            out.writeInt(sizeOfRootBlock);

            out.write(a, 0, sizeOfRootBlock);

            out.writeLong(sizeOnDisk);

        }

        @Override
        public void readExternal(final ObjectInput in) throws IOException,
                ClassNotFoundException {

            final int version = in.readInt();

            switch (version) {
            case VERSION0:
                break;
            default:
                throw new IOException("Unknown version: " + version);
            }

            final int sizeOfRootBlock = in.readInt();

            final byte[] a = new byte[sizeOfRootBlock];

            in.readFully(a, 0, sizeOfRootBlock);
            
            rootBlock = new RootBlockView(false/* rootBlock0 */,
                    ByteBuffer.wrap(a), ChecksumUtility.getCHK());

            sizeOnDisk = in.readLong();
            
        }

        @Override
        public long getCommitCounter() {
            return getRootBlock().getCommitCounter();
        }

        @Override
        public long getCommitTime() {
            return getRootBlock().getLastCommitTime();
        }
        
    } // SnapshotRecord
    
    /**
     * Encapsulates key and value formation.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     */
    static protected class TupleSerializer extends
            DefaultTupleSerializer<Long, ISnapshotRecord> {

        /**
         * 
         */
        private static final long serialVersionUID = -2851852959439807542L;

        /**
         * De-serialization ctor.
         */
        public TupleSerializer() {

            super();
            
        }

        /**
         * Ctor when creating a new instance.
         * 
         * @param keyBuilderFactory
         */
        public TupleSerializer(final IKeyBuilderFactory keyBuilderFactory) {

            super(keyBuilderFactory);

        }
        
        /**
         * Decodes the key as a commit time.
         */
        @Override
        @SuppressWarnings("rawtypes") 
        public Long deserializeKey(final ITuple tuple) {

            return KeyBuilder
                    .decodeLong(tuple.getKeyBuffer().array(), 0/* offset */);

        }

//        /**
//         * De-serializes an object from the {@link ITuple#getValue() value} stored
//         * in the tuple (ignores the key stored in the tuple).
//         */
//        public ISnapshotRecord deserialize(final ITuple tuple) {
//
//            if (tuple == null)
//                throw new IllegalArgumentException();
//
//            return (IRootBlockView) new RootBlockView(false/* rootBlock0 */,
//                    ByteBuffer.wrap(tuple.getValue()), ChecksumUtility.getCHK());
//            
//        }

        /**
         * The initial version (no additional persistent state).
         */
        private final static transient byte VERSION0 = 0;

        /**
         * The current version.
         */
        private final static transient byte VERSION = VERSION0;

        public void readExternal(final ObjectInput in) throws IOException,
                ClassNotFoundException {

            super.readExternal(in);
            
            final byte version = in.readByte();
            
            switch (version) {
            case VERSION0:
                break;
            default:
                throw new UnsupportedOperationException("Unknown version: "
                        + version);
            }

        }

        public void writeExternal(final ObjectOutput out) throws IOException {

            super.writeExternal(out);
            
            out.writeByte(VERSION);
            
        }

    }

}
