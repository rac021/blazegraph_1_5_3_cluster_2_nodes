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
 * Created on Jun 29, 2010
 */

package com.bigdata.quorum.zk;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.rmi.Remote;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.BadVersionException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.KeeperException.NotEmptyException;
import org.apache.zookeeper.KeeperException.SessionExpiredException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;

import com.bigdata.ha.HAPipelineGlue;
import com.bigdata.io.SerializerUtil;
import com.bigdata.jini.start.BigdataZooDefs;
import com.bigdata.quorum.AbstractQuorum;
import com.bigdata.quorum.Quorum;
import com.bigdata.quorum.QuorumActor;
import com.bigdata.quorum.QuorumClient;
import com.bigdata.quorum.QuorumEventEnum;
import com.bigdata.quorum.QuorumException;
import com.bigdata.quorum.QuorumMember;
import com.bigdata.quorum.QuorumWatcher;
import com.bigdata.util.InnerCause;
import com.bigdata.util.concurrent.DaemonThreadFactory;

/**
 * Implementation of the {@link Quorum} using zookeeper to maintain the
 * distributed quorum state. This implementation includes a zookeeper aware
 * {@link QuorumWatcher} which registers patterns of watchers in order to be
 * notified of changes in the distributed quorum state. It also includes a
 * {@link QuorumActor} which knows how to make the various changes in the
 * distributed quorum state within zookeeper.
 * <p>
 * Note: If the {@link ZKQuorumImpl} fails for some reason to track state
 * changes in zookeeper, then you can always close the current {@link ZooKeeper}
 * connection and it will repopulate its internal state. Of course, this has the
 * effect of failing the {@link QuorumMember}'s service. That generally has
 * minor costs unless it is the quorum leader which is failed.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 * 
 * @todo adapt the existing unit tests to run against a zookeeper instance.
 * 
 * @todo Unit tests for {@link ZooKeeper} connection close and reopen to verify
 *       that the internal state gets caught up with the distributed state when
 *       the new session is established. These tests should explore situations
 *       where the distributed quorum state evolves before the client is
 *       reconnected. That will cause the client's internal state model to be
 *       partly inconsistent and can be used to test not only that the client
 *       can get reconnected, but also how it catches up with the actual state
 *       of the distributed quorum.
 * 
 *       FIXME - ZKQuorumImpl currently uses ZKQuorumClient for a generic type.
 *       This is causing some bounds mismatch problems. I think that we should
 *       back the ZKQuorumClient out of the generic type and use a runtime
 *       assertion in ZKQuorumImpl.start(QuorumClient) to make sure that we are
 *       using the right kind of QuorumClient.
 */
public class ZKQuorumImpl<S extends Remote, C extends ZKQuorumClient<S>> extends
        AbstractQuorum<S, C> implements ZKQuorum<S, C> {

    /**
     * Return the ACLs used by the quorum actor and watcher.
     * 
     * @throws IllegalStateException
     *             if the client is not running.
     */
    public List<ACL> getZookeeperACL() {

        return getClientNoLock().getACL();
        
    }

    /**
     * The {@link ZooKeeper} connection for the client.
     * 
     * @return The {@link ZooKeeper} connection.
     * @throws InterruptedException
     *             never
     * @throws IllegalStateException
     *             if the client is not running.
     * 
     *             TODO Remove InterruptedException?
     */
    public ZooKeeper getZookeeper() throws InterruptedException {
    
        return getClientNoLock().getZooKeeper();

    }

    /**
     * 
     * @param k The replication factor.
     */
    public ZKQuorumImpl(final int k) {

        super(k);
        
//        if(zka == null)
//            throw new IllegalArgumentException();
//
//        if(acl == null)
//            throw new IllegalArgumentException();

//        this.zka = zka;
//        
//        this.acl = acl;

    }

    @Override
    protected QuorumActorBase newActor(final String logicalServiceId,
            final UUID serviceId) {

        return new ZkQuorumActor(logicalServiceId, serviceId);

    }

    @Override
    protected QuorumWatcherBase newWatcher(final String logicalServiceId) {

        return new ZkQuorumWatcher(logicalServiceId);

    }

    /**
     * Zookeeper provides a name uniqueness guarantee and <em>unordered</em>
     * children. Applications impose order on the children by using the
     * SEQUENTIAL {@link CreateMode}s and then sorting the children lexically.
     * However, when the SEQUENTIAL {@link CreateMode}s are used, all children
     * will have unique names and the application most impose an uniqueness
     * constraint on the behalf of its {@link QuorumMember}.
     * <p>
     * The criterion for uniqueness for a {@link QuorumMember} is whether there
     * exists another child having the same ephemeral znode, which corresponds
     * to the {@link ZooKeeper} connection object. This information is carried
     * by the {@link Stat#getEphemeralOwner() ephemeralOwner} attribute on each
     * znode. This means that in order to verify uniqueness we must not only get
     * the children of a znode, such as {@link ZKQuorum#QUORUM_VOTES}, but we
     * must also get each child and examine its {@link Stat} structure to verify
     * that no child has the same <i>ephemeralOwner</i> as is reported by
     * {@link ZooKeeper#getSessionId()}. Alternatively, the
     * {@link ZkQuorumActor} may inspect the <em>ServiceUUID</em> attributes in
     * the data for each child, which amounts to the same thing but could also
     * be used to impose a uniqueness constraint for persistent children (this
     * is not something that we currently do).
     * <p>
     * The other approach to enforcing uniqueness is to rely on the coordinated
     * behavior or the {@link ZkQuorumActor} and the {@link ZkQuorumWatcher} for
     * the same {@link QuorumMember}. However, even though
     * {@link QuorumActorBase} acquire a lock and conditionally executes only
     * those actions which whose postcondition is not already satisfied by the
     * {@link AbstractQuorum}'s internal copy of the quorum state, the methods
     * on {@link QuorumActorBase} can return before the effect of the action is
     * noticed by the {@link QuorumWatcher}. This creates a gap during which a
     * client could reissue the same action, e.g.,
     * {@link QuorumActor#pipelineAdd()} and have the {@link #doPipelineAdd()}
     * executed twice by {@link QuorumActorBase}. Since we must use
     * {@link CreateMode#EPHEMERAL_SEQUENTIAL} in order to impose order on the
     * pipeline, the double invocation of {@link #doPipelineAdd()} will cause
     * multiple children to be created for the {@link QuorumMember}. In order to
     * close that gap we would have to explicitly wait until the
     * {@link ZkQuorumWatcher} noticed the state change and updated the
     * {@link AbstractQuorum}'s internal copy of the quorum state.
     * <p>
     * Another way to close this gap would be to maintain a model in the
     * {@link ZkQuorumActor} of the association between the {@link QuorumMember}
     * and the corresponding zpath in the pipeline, the votes, and the joined
     * services (we do not need to do this for quorum members because those do
     * not use the SEQUENTIAL create mode). The internal model would be updated
     * when zookeeper indicates that the operation was successfully executed by
     * a normal return code (this assumes that zookeeper will retry
     * communications until it times out the {@link ZooKeeper} session). That
     * internal model would only have to track those aspects of the quorum state
     * which pertain to the {@link QuorumMember} on whose behalf the
     * {@link ZkQuorumActor} acts. The model WOULD NOT have to be shared with
     * the {@link ZkQuorumWatcher}. However, the model would have to be cleared
     * if the {@link ZooKeeper} connection is lost.
     * <p>
     * Note: This approach does not protect against state changes made by other
     * zookeeper clients to the quorum state of our {@link QuorumMember}. For
     * example, if someone deletes the znode for our {@link QuorumMember} from
     * the pipeline, then the {@link ZkQuorumActor}'s internal state model will
     * not observe the update. Such changes should not be made by other
     * processes. However, the only way to accommodate such changes is to expose
     * the {@link ZkQuorumActor}'s internal state model to the
     * {@link ZkQuorumWatcher} so it notices such state changes.
     * <p>
     * Holding the same {@link ZooKeeper} connection object across an operation
     * ensures that the action is atomic from the perspective of this actor.
     * However, a higher guarantee of atomic action could be obtain by first
     * noticing the {@link Stat} of the parent znode ("joined", "pipeline", and
     * specific lastCommitTime values) and then verifying that it had not been
     * changed before we create the znode with the SEQUENTIAL flag. If it has
     * been changed, then we can just retry in a loop halting when we find that
     * the znode for the service is exists or when we manage to atomically
     * create the znode for the service. The atomicity here arises from the
     * version change in the {@link Stat} of the parent when the children of the
     * parent are modified (add/remove). However, this is only an issue if there
     * is an attempt by an operator or the same of a different ZooKeeper process
     * to join this service. Since those conditions are not anticipated, the
     * extra validation logic has not been put into place yet.
     */
    protected class ZkQuorumActor extends QuorumActorBase {

        /**
         * The value of the service's {@link UUID} as a {@link String} (we use
         * this a lot).
         */
        final private String serviceIdStr;

//        /**
//         * The absolute zpath of the {@link CreateMode#EPHEMERAL} znode for the
//         * quorum member under the {@link ZKQuorum#QUORUM_MEMBER} znode.
//         * <p>
//         * Note: This field is not strictly necessary because the member does
//         * not use the SEQUENTIAL flag and we can rely on the znode name to
//         * provide a guarantee of uniqueness.
//         */
//        private String memberZPath = null;
//
//        /**
//         * The absolute zpath of the {@link CreateMode#EPHEMERAL_SEQUENTIAL}
//         * znode for the {@link QuorumMember} under the relevant lastCommitTime
//         * under {@link ZKQuorum#QUORUM_VOTES}.
//         */
//        private String voteZPath = null;
//
//        /**
//         * The absolute zpath of the {@link CreateMode#EPHEMERAL_SEQUENTIAL}
//         * znode for the {@link QuorumMember} under the
//         * {@link ZKQuorum#QUORUM_PIPELINE} znode and <code>null</code> when the
//         * {@link QuorumMember} is not part of the pipeline.
//         */
//        private String pipelineZPath = null;
//
//        /**
//         * The absolute zpath of the {@link CreateMode#EPHEMERAL_SEQUENTIAL}
//         * znode for the {@link QuorumMember} under the
//         * {@link ZKQuorum#QUORUM_JOINED} znode and <code>null</code> when the
//         * {@link QuorumMember} is joined with the quorum.
//         */
//        private String joinedZPath = null;

        protected ZkQuorumActor(final String logicalServiceZPath,
                final UUID serviceId) {

            super(logicalServiceZPath, serviceId);

            serviceIdStr = serviceId.toString();

        }

        @Override
        protected void doMemberAdd() {
            // get a valid zookeeper connection object.
            final ZooKeeper zk;
            try {
                zk = getZookeeper();
            } catch (InterruptedException e) {
                // propagate the interrupt.
                Thread.currentThread().interrupt();
                return;
            }
            try {
                final QuorumServiceState state = new QuorumServiceState(
                        serviceId);
                zk.create(logicalServiceId + "/" + QUORUM + "/" + QUORUM_MEMBER
                        + "/" + QUORUM_MEMBER_PREFIX + serviceIdStr,
                        SerializerUtil.serialize(state), getZookeeperACL(),
                        CreateMode.EPHEMERAL);
            } catch (NodeExistsException e) {
                // ignore.
            } catch (KeeperException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                // propagate the interrupt.
                Thread.currentThread().interrupt();
                return;
            }
        }

        @Override
        protected void doMemberRemove(final UUID service) {
            // get a valid zookeeper connection object.
            final ZooKeeper zk;
            try {
                zk = getZookeeper();
            } catch (InterruptedException e) {
                // propagate the interrupt.
                Thread.currentThread().interrupt();
                return;
            }
            try {
                zk.delete(logicalServiceId + "/" + QUORUM + "/"
                                + QUORUM_MEMBER + "/" + QUORUM_MEMBER_PREFIX
                                + service.toString(), -1/* anyVersion */);
            } catch (NoNodeException e) {
                // ignore.
            } catch (KeeperException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                // propagate the interrupt.
                Thread.currentThread().interrupt();
                return;
            }
        }

        @Override
        protected void doPipelineAdd() {
            // resolve the local service implementation object.
            final HAPipelineGlue tmp = (HAPipelineGlue) getMember()
                    .getService();
            final InetSocketAddress addrNext;
            try {
                addrNext = tmp.getWritePipelineAddr();
            } catch (IOException e1) {
                throw new RuntimeException(e1);
            }
            // ask it for the write pipeline's address and port.
            final QuorumPipelineState pipelineState = new QuorumPipelineState(
                    serviceId, addrNext);
            // get a valid zookeeper connection object.
            final ZooKeeper zk;
            try {
                zk = getZookeeper();
            } catch (InterruptedException e) {
                // propagate the interrupt.
                Thread.currentThread().interrupt();
                return;
            }
            try {
                /*
                 * First, look for an existing znode for this service.
                 */
                final String zpath = logicalServiceId + "/" + QUORUM + "/"
                        + QUORUM_PIPELINE;
                // get children, resetting the watch as a side-effect.
                final String[] children = zk
                        .getChildren(zpath, false/* watch */).toArray(
                                new String[0]);
                // lexiographic sort orders children by sequential suffixes.
                Arrays.sort(children);
                {
                    for (String s : children) {
                        final QuorumServiceState state = (QuorumServiceState) SerializerUtil
                                .deserialize(zk.getData(zpath + "/" + s,
                                        false/* watch */, null/* stat */));
                        if (serviceId.equals(state.serviceUUID())) {
                            log.warn("Service already in pipeline");
                            return;
                        }
                    }
                }
                /*
                 * Since there is no znode for this service, create one now.
                 */
                zk.create(zpath + "/" + QUORUM_PIPELINE_PREFIX, SerializerUtil
                        .serialize(pipelineState), getZookeeperACL(),
                        CreateMode.EPHEMERAL_SEQUENTIAL);
            } catch (KeeperException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                // propagate the interrupt.
                Thread.currentThread().interrupt();
                return;
            }
        }

        @Override
        protected void doPipelineRemove(final UUID service) {
            // get a valid zookeeper connection object.
            final ZooKeeper zk;
            try {
                zk = getZookeeper();
            } catch (InterruptedException e) {
                // propagate the interrupt.
                Thread.currentThread().interrupt();
                return;
            }
            try {
                final String zpath = logicalServiceId + "/" + QUORUM + "/"
                        + QUORUM_PIPELINE;
                // get children, resetting the watch as a side-effect.
                final String[] children = zk
                        .getChildren(zpath, false/* watch */).toArray(
                                new String[0]);
                // lexiographic sort orders children by sequential suffixes.
                Arrays.sort(children);
                {
                    for (String s : children) {
                        // Get the data for that znode.
                        final byte[] b;
                        try {
                            b = zk.getData(zpath + "/" + s, false/* watch */,
                                    null/* stat */);
                        } catch (NoNodeException ex) {
                            // Concurrent remove of some znode.
                            continue;
                        }
                        final QuorumServiceState state = (QuorumServiceState) SerializerUtil
                                .deserialize(b);
                        if (service.equals(state.serviceUUID())) {
                            zk.delete(zpath + "/" + s, -1/* anyVersion */);
                            return;
                        }
                    }
                }
            } catch (KeeperException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                // propagate the interrupt.
                Thread.currentThread().interrupt();
                return;
            }
        }

//        /**
//         * Directs each service before this service in the write pipeline to
//         * move itself to the end of the write pipeline.
//         * 
//         * @todo optimize the write pipeline for the network topology.
//         */
//        @Override
//        protected boolean reorganizePipeline() {
//            final UUID[] pipeline = getPipeline();
//            final UUID[] joined = getJoinedMembers();
//            final UUID leaderId = joined[0];
////            System.err.println("pipeline="+Arrays.toString(pipeline));
////            System.err.println("joined  ="+Arrays.toString(pipeline));
////            System.err.println("leader  ="+leaderId);
////            System.err.println("self    ="+serviceId);
//            boolean modified = false;
//            for (int i = 0; i < pipeline.length; i++) {
//                final UUID otherId = pipeline[i];
//                if (leaderId.equals(otherId)) {
//                    // Done.
//                    return modified;
//                }
//                // some other service in the pipeline ahead of the leader.
//                final S otherService = getQuorumMember().getService(otherId);
//                if (otherService == null) {
//                    throw new QuorumException(
//                            "Could not discover service: serviceId="
//                                    + serviceId);
//                }
//                try {
//                    // ask it to move itself to the end of the pipeline.
//                    ((HAPipelineGlue) otherService).moveToEndOfPipeline().get();
//                } catch (IOException ex) {
//                    throw new QuorumException(
//                            "Could not move service to end of the pipeline: serviceId="
//                                    + serviceId+", otherId="+otherId, ex);
//                } catch (InterruptedException e) {
//                    // propagate interrupt.
//                    Thread.currentThread().interrupt();
//                    return modified;
//                } catch (ExecutionException e) {
//                    throw new QuorumException(
//                            "Could not move service to end of the pipeline: serviceId="
//                                    + serviceId + ", otherId=" + otherId, e);
//                }
////                System.err.println("moved   ="+otherId);
////                System.err.println("pipeline="+Arrays.toString(getPipeline()));
//                modified = true;
//            }
//            return modified;
//        }

        /**
         * Return the zpath for the VOTES for the logical service to which this
         * service belongs.
         */
        private String getVotesZPath() {

            // TODO Extract constant.
            return logicalServiceId + "/" + QUORUM + "/" + QUORUM_VOTES;
            
        }
        
        /**
         * Return the zpath for the given last commit time for the logical
         * service to which this service belongs.
         * 
         * @param lastCommitTime
         *            The last commit time.
         * 
         * @return The zpath.
         */
//        * <p>
//        * Note: The lastCommitTime znode name is formatted with 20 digits and
//        * leading zeros in order to allow any possible last commit time values
//        * while preserving the ability to order them by a lexical sort on the
//        * zpaths or znode names.
        private String getLastCommitTimeZPath(final long lastCommitTime) {
            
            final StringBuilder sb = new StringBuilder();

            sb.append(getVotesZPath());

            sb.append("/");
            sb.append(lastCommitTime);
//            final Formatter f = new Formatter(sb);
//
//            f.format("%020d", lastCommitTime);
//            f.flush();
//            f.close();

            // zpath for the lastCommitTime.
            final String lastCommitTimeZPath = sb.toString();
            
            return lastCommitTimeZPath;
            
        }
        
        @Override
        protected void doCastVote(final long lastCommitTime) {
            final String lastCommitTimeZPath = getLastCommitTimeZPath(lastCommitTime);
            if (log.isInfoEnabled())
                log.info("lastCommitTime=" + lastCommitTime
                        + ", lastCommitTimeZPath=" + lastCommitTimeZPath);
            // get a valid zookeeper connection object.
            final ZooKeeper zk;
            try {
                zk = getZookeeper();
            } catch (InterruptedException e) {
                // propagate the interrupt.
                Thread.currentThread().interrupt();
                return;
            }
            while (true) {
                // create znode for the lastCommitTime.
                try {
                    zk.create(lastCommitTimeZPath, new byte[0]/* empty */,
                            getZookeeperACL(), CreateMode.PERSISTENT);
                } catch (NodeExistsException e) {
                    // ignore.
                } catch (KeeperException e) {
                    throw new RuntimeException(e);
                } catch (InterruptedException e) {
                    // propagate the interrupt.
                    Thread.currentThread().interrupt();
                    return;
                }
                /*
                 * Create znode for this service's vote for that lastCommitTime.
                 */
                try {
                    final QuorumServiceState state = new QuorumServiceState(
                            serviceId);
                    // voteZPath =
                    zk.create(lastCommitTimeZPath + "/"
                            + ZKQuorum.QUORUM_VOTE_PREFIX, SerializerUtil
                            .serialize(state), getZookeeperACL(),
                            CreateMode.EPHEMERAL_SEQUENTIAL);
                } catch (NoNodeException e) {
                    /*
                     * The parent znode was concurrently deleted, e.g., by
                     * doWithdrawVote(). This is handled by a simple retry loop
                     * when casting a vote. The logic to delete lastCommitTime
                     * znodes without children DOES NOT retry. Since a delete
                     * only occurs when there was only a single child for that
                     * lastCommitTime, there should be at most one retry (more
                     * than one retries would imply that a different service had
                     * managed to vote and then withdraw its vote before this
                     * service was able to finish casting its vote).
                     */
                    log.warn("Concurrent delete (retrying): zpath="
                            + lastCommitTimeZPath);
                } catch (KeeperException e) {
                    throw new RuntimeException(e);
                } catch (InterruptedException e) {
                    // propagate the interrupt.
                    Thread.currentThread().interrupt();
                    return;
                }
                // Success.
                return;
            }
        }

        /**
         * Searches for a vote cast by this service. If found, it is removed. If
         * there are no remaining votes for the lastCommitTime from which it was
         * removed, then the lastCommitTime znode is deleted.
         * <p>
         * When we delete lastCommitTime znode with no children, we ignore any
         * errors since there could be a concurrent create in response to a vote
         * cast by another service. For its part, {@link #castVote(long)}
         * handles a concurrent delete by a simple retry loop.
         */
        @Override
        protected void doWithdrawVote(final UUID service) {
            // zpath for votes.
            final String votesZPath = getVotesZPath();
            if (log.isInfoEnabled())
                log.info("votesZPath=" + votesZPath);
            // get a valid zookeeper connection object.
            final ZooKeeper zk;
            try {
                zk = getZookeeper();
            } catch (InterruptedException e) {
                // propagate the interrupt.
                Thread.currentThread().interrupt();
                return;
            }
            // find all lastCommitTimes for which votes have been cast.
            final String[] lastCommitTimes;
            try {
                final List<String> tmp = zk
                        .getChildren(votesZPath, false/* watch */);
                lastCommitTimes = tmp.toArray(new String[tmp.size()]);
                Arrays.sort(lastCommitTimes,VoteComparator.INSTANCE); // Note: ascending order!
            } catch (KeeperException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                // propagate the interrupt.
                Thread.currentThread().interrupt();
                return;
            }
            // search for a vote for this service.
            for (String lastCommitTime : lastCommitTimes) {
                // zpath for votes for that lastCommitTime.
                final String lastCommitTimeZPath = votesZPath + "/"
                        + lastCommitTime;
                // get the votes for that lastCommitTime.
                final List<String> votes;
                try {
                    votes = zk
                            .getChildren(lastCommitTimeZPath, false/* watch */);
                } catch (NoNodeException ex) {
                    // ignore (concurrent delete, so there are no children).
                    continue;
                } catch (KeeperException ex) {
                    throw new RuntimeException(ex);
                } catch (InterruptedException e) {
                    // propagate the interrupt.
                    Thread.currentThread().interrupt();
                    return;
                }
                if (votes.isEmpty()) {
                    /*
                     * There are no votes for this lastCommitTime, so try ONCE
                     * to remove the znode for that lastCommitTime.
                     */
                    try {
                        // delete the service znode for this lastCommitTime.
                        zk.delete(lastCommitTimeZPath, -1/* anyVersion */);
                    } catch (NoNodeException e) {
                        // ignore (concurrent delete).
                        continue;
                    } catch (NotEmptyException e) {
                        // ignore (concurrent create of child (cast vote)).
                        continue;
                    } catch (KeeperException e) {
                        throw new RuntimeException(e);
                    } catch (InterruptedException e) {
                        // propagate the interrupt.
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                // try each vote for this lastCommitTime.
                for (String vote : votes) {
                    final String voteZPath = lastCommitTimeZPath + "/" + vote;
                    final QuorumServiceState state;
                    try {
                        state = (QuorumServiceState) SerializerUtil
                                .deserialize(zk.getData(voteZPath,
                                        false/* watch */, null/* stat */));
                    } catch (NoNodeException e) {
                        // concurrent delete of some cast vote.
                        continue;
                    } catch (KeeperException e) {
                        throw new RuntimeException(e);
                    } catch (InterruptedException e) {
                        // propagate the interrupt.
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (service.equals(state.serviceUUID())) {
                        // found our vote.
                        try {
                            // delete our vote.
                            zk.delete(voteZPath, -1/* anyVersion */);
                            /*
                             * If successful, then the service had a vote cast
                             * for that last commit time.
                             */
                            if (votes.size() == 1) {
                                /*
                                 * Since there were no other votes for that
                                 * lastCommitTime, try ONCE to remove the znode
                                 * for that lastCommitTime.
                                 */
                                try {
                                    // delete znode for this lastCommitTime.
                                    zk.delete(lastCommitTimeZPath, -1/* anyVersion */);
                                } catch (NoNodeException e) {
                                    // ignore (concurrent delete).
                                    continue;
                                } catch (NotEmptyException e) {
                                    /*
                                     * ignore (concurrent create of child
                                     * (someone else cast a vote concurrently)).
                                     */
                                    continue;
                                } catch (KeeperException e) {
                                    throw new RuntimeException(e);
                                } catch (InterruptedException e) {
                                    // propagate the interrupt.
                                    Thread.currentThread().interrupt();
                                    return;
                                }
                            }
                            // done.
                            if (log.isInfoEnabled())
                                log.info("withdrawn: serviceId=" + service.toString()
                                        + ", lastCommitTime=" + lastCommitTime);
                            return;
                        } catch (NoNodeException e) {
                            /*
                             * ignore (the service does not have a vote cast for
                             * that lastCommitTime).
                             */
                            continue;
                        } catch (KeeperException e) {
                            throw new RuntimeException(e);
                        } catch (InterruptedException e) {
                            // propagate the interrupt.
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }

        }

        @Override
        protected void doServiceJoin() {
            // get a valid zookeeper connection object.
            final ZooKeeper zk;
            try {
                zk = getZookeeper();
            } catch (InterruptedException e) {
                // propagate the interrupt.
                Thread.currentThread().interrupt();
                return;
            }
            /*
             * First, verify no entry for this service.
             */
            try {
                final String zpath = logicalServiceId + "/" + QUORUM + "/"
                        + QUORUM_JOINED;
                // get children, resetting the watch as a side-effect.
                final String[] children = zk
                        .getChildren(zpath, false/* watch */).toArray(
                                new String[0]);
                // lexiographic sort orders children by sequential suffixes.
                Arrays.sort(children);
                {
                    for (String s : children) {
                        final QuorumServiceState state = (QuorumServiceState) SerializerUtil
                                .deserialize(zk.getData(zpath + "/" + s,
                                        false/* watch */, null/* stat */));
                        if (serviceId.equals(state.serviceUUID())) {
                            // Found this service.
                            log.warn("Service " + serviceId + " already joined in quorum of " + children.length);
                            return;
                        }
                    }
                }
                /*
                 * Now, create the znode for this service.
                 */
                final QuorumServiceState state = new QuorumServiceState(
                        serviceId);
                zk.create(zpath + "/" + QUORUM_JOINED_PREFIX, SerializerUtil
                        .serialize(state), getZookeeperACL(),
                        CreateMode.EPHEMERAL_SEQUENTIAL);
            } catch (KeeperException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                // propagate the interrupt.
                Thread.currentThread().interrupt();
                return;
            }
        }

        @Override
        protected void doServiceLeave(final UUID service) {
            // get a valid zookeeper connection object.
            final ZooKeeper zk;
            try {
                zk = getZookeeper();
            } catch (InterruptedException e) {
                // propagate the interrupt.
                Thread.currentThread().interrupt();
                return;
            }
            /*
             * Locate the znode for this service.
             */
            try {
                final String zpath = logicalServiceId + "/" + QUORUM + "/"
                        + QUORUM_JOINED;
                // get children, resetting the watch as a side-effect.
                final String[] children = zk
                        .getChildren(zpath, false/* watch */).toArray(
                                new String[0]);
                // lexiographic sort orders children by sequential suffixes.
                Arrays.sort(children);
                {
                    for (String s : children) {
                        // Examine the data for that child.
                        final byte[] b;
                        try {
                            b = zk.getData(zpath + "/" + s, false/* watch */,
                                    null/* stat */);
                        } catch (NoNodeException ex) {
                            // Concurrent remove of some znode.
                            continue;
                        }
                        final QuorumServiceState state = (QuorumServiceState) SerializerUtil
                                .deserialize(b);
                        if (service.equals(state.serviceUUID())) {
                            // Found this service.
                            zk.delete(zpath + "/" + s, -1/* anyVersion */);
                            return;
                        }
                    }
                }
//            } catch (NoNodeException e) {
//                // ignore.
            } catch (KeeperException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                // propagate the interrupt.
                Thread.currentThread().interrupt();
                return;
            }
        }

        /**
         * @todo Make sure the quorum breaks when the leader dies as well as
         *       when it leaves nicely. To do this, any quorum member which
         *       observes that the #of joined services falls beneath (k+1/2)
         *       should attempt to clear the token. Verify this in the code and
         *       with unit tests.
         */
        @Override
        protected void doClearToken() {
            /*
             * Try in a loop until we can read the data and update it without
             * having a concurrent update. If the token has been cleared, then
             * we are done (it does not matter which service observing a quorum
             * break actually clears the token, only that it is quickly
             * cleared).
             */
            // get a valid zookeeper connection object.
            final ZooKeeper zk;
            try {
                zk = getZookeeper();
            } catch (InterruptedException e) {
                // propagate the interrupt.
                Thread.currentThread().interrupt();
                return;
            }
            while (true) {
                /*
                 * Read the current quorum state.
                 */
                final Stat stat = new Stat();
                final QuorumTokenState oldState;
                try {
                    oldState = (QuorumTokenState) SerializerUtil.deserialize(zk
                            .getData(logicalServiceId + "/" + QUORUM,
                                    false/* watch */, stat));
                } catch (KeeperException e) {
                    throw new RuntimeException(e);
                } catch (InterruptedException e) {
                    // propagate the interrupt.
                    Thread.currentThread().interrupt();
                    return;
                }
                if (oldState.token() == Quorum.NO_QUORUM) {
                    // done (the token is not set).
                    return;
                }
                // new local state object w/ the current token cleared.
                final QuorumTokenState newState = new QuorumTokenState(oldState
                        .lastValidToken(), Quorum.NO_QUORUM, replicationFactor());
                try {
//    log.fatal("Setting quorum state: \noldState="+oldState+"\nnewState="+newState); 
                    zk.setData(logicalServiceId + "/" + QUORUM, SerializerUtil
                            .serialize(newState), stat.getVersion());
                    // done.
                    if (log.isInfoEnabled())
                        log.info("Cleared token: serviceId=" + serviceId);
                    return;
                } catch (BadVersionException e) {
                    /*
                     * If we get a version conflict, then just retry. Either the
                     * token was cleared by someone else or we will try to clear
                     * it ourselves.
                     */
                    log.warn("Concurrent update (retry): serviceId="
                            + serviceIdStr);
                    continue;
                } catch (KeeperException e) {
                    throw new RuntimeException(e);
                } catch (InterruptedException e) {
                    // propagate the interrupt.
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        @Override
        protected void doSetToken(final long newToken) {
            if (newToken == NO_QUORUM)
                throw new IllegalArgumentException();
            // get a valid zookeeper connection object.
            final ZooKeeper zk;
            try {
                zk = getZookeeper();
            } catch (InterruptedException e) {
                // propagate the interrupt.
                Thread.currentThread().interrupt();
                return;
            }
            /*
             * Try in a loop until we can read the data and update it without
             * having a concurrent update. If the token has been cleared, then
             * we are done (it does not matter which service observing a quorum
             * break actually clears the token, only that it is quickly
             * cleared). If the token has been concurrently updated, then we are
             * done (the lastValidToken must strictly advance).
             * 
             * Note: Conflicts can come from ANY change to the QUORUM znode's
             * state.
             */
            while (true) {
                /*
                 * Read the current quorum state.
                 */
                final Stat stat = new Stat();
                final QuorumTokenState oldState;
                try {
                    oldState = (QuorumTokenState) SerializerUtil.deserialize(zk
                            .getData(logicalServiceId + "/" + QUORUM,
                                    false/* watch */, stat));
                } catch (KeeperException e) {
                    throw new RuntimeException(e);
                } catch (InterruptedException e) {
                    // propagate the interrupt.
                    Thread.currentThread().interrupt();
                    return;
                }
                /*
                 * Check some preconditions.
                 */
                if (oldState.lastValidToken() >= newToken) {
                    // the lastValidToken must advance.
                    throw new QuorumException(
                            "New value must be GT old value: oldValue="
                                    + oldState.lastValidToken()
                                    + ", but newValue=" + newToken);
                }
                if (oldState.token() != Quorum.NO_QUORUM) {
                    /*
                     * A new value for lastValidToken should not be assigned
                     * unless the quorum has broken. The quorum token should
                     * have been cleared when the quorum broken.
                     */
                    throw new QuorumException(
                            "The quorum token has not been cleared");
                }
                try {
                    // new local state object.
                    final QuorumTokenState newState = new QuorumTokenState(
                            newToken, newToken, replicationFactor());
//    log.fatal("Setting quorum state: \noldState="+oldState+"\nnewState="+newState);
                    // update data (verifying the version!)
                    zk.setData(logicalServiceId + "/" + QUORUM, SerializerUtil
                            .serialize(newState), stat.getVersion());
                    // done.
                    if (log.isInfoEnabled())
                        log.info("Set: lastValidToken=" + newToken);
                    return;
                } catch (BadVersionException e) {
                    /*
                     * If we get a version conflict, then just retry. Either the
                     * token was cleared by someone else or we will try to clear
                     * it ourselves.
                     */
                    log.warn("Concurrent update (retry): serviceId="
                            + serviceIdStr);
                    continue;
                } catch (KeeperException e) {
                    throw new RuntimeException(e);
                } catch (InterruptedException e) {
                    // propagate the interrupt.
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    
//        @Override
//        protected void doSetLastValidToken(final long newToken) {
//            // get a valid zookeeper connection object.
//            final ZooKeeper zk;
//            try {
//                zk = getZookeeper();
//            } catch (InterruptedException e) {
//                // propagate the interrupt.
//                Thread.currentThread().interrupt();
//                return;
//            }
//            /*
//             * Try in a loop until we can read the data and update it without
//             * having a concurrent update. If the token has been cleared, then
//             * we are done (it does not matter which service observing a quorum
//             * break actually clears the token, only that it is quickly
//             * cleared).
//             * 
//             * Note: Conflicts can come from ANY change to the QUORUM znode's
//             * state.
//             */
//            while (true) {
//                /*
//                 * Read the current quorum state.
//                 */
//                final Stat stat = new Stat();
//                final QuorumTokenState oldState;
//                try {
//                    oldState = (QuorumTokenState) SerializerUtil.deserialize(zk
//                            .getData(logicalServiceId + "/" + QUORUM,
//                                    false/* watch */, stat));
//                } catch (KeeperException e) {
//                    throw new RuntimeException(e);
//                } catch (InterruptedException e) {
//                    // propagate the interrupt.
//                    Thread.currentThread().interrupt();
//                    return;
//                }
//                /*
//                 * Check some preconditions.
//                 */
//                if (oldState.lastValidToken() >= newToken) {
//                    // the lastValidToken must advance.
//                    throw new QuorumException(
//                            "New value must be GT old value: oldValue="
//                                    + oldState.lastValidToken()
//                                    + ", but newValue=" + newToken);
//                }
//                if (oldState.token() != Quorum.NO_QUORUM) {
//                    /*
//                     * A new value for lastValidToken should not be assigned
//                     * unless the quorum has broken. The quorum token should
//                     * have been cleared when the quorum broken.
//                     */
//                    throw new QuorumException(
//                            "The quorum token has not been cleared");
//                }
//                try {
//                    // new local state object w/ the current token cleared.
//                    final QuorumTokenState newState = new QuorumTokenState(
//                            newToken, oldState.token());
//                    // update data (verifying the version!)
//                    zk.setData(logicalServiceId + "/" + QUORUM, SerializerUtil
//                            .serialize(newState), stat.getVersion());
//                    // done.
//                    if (log.isInfoEnabled())
//                        log.info("Set: lastValidToken=" + newToken);
//                    return;
//                } catch (BadVersionException e) {
//                    /*
//                     * If we get a version conflict, then just retry. Either the
//                     * token was cleared by someone else or we will try to clear
//                     * it ourselves.
//                     */
//                    log.warn("Concurrent update (retry): serviceId="
//                            + serviceIdStr);
//                    continue;
//                } catch (KeeperException e) {
//                    throw new RuntimeException(e);
//                } catch (InterruptedException e) {
//                    // propagate the interrupt.
//                    Thread.currentThread().interrupt();
//                    return;
//                }
//            }
//        }
//
//        /**
//         * @todo For zookeeper, we can atomically update both the lastValidToken
//         *       and the currentToken. This might be true in general. [Change
//         *       the quorum dynamics to reflect this.]
//         */
//        @Override
//        protected void doSetToken() {
//            /*
//             * Try in a loop until we can read the data and update it without
//             * having a concurrent update. If the token has been cleared, then
//             * we are done (it does not matter which service observing a quorum
//             * break actually clears the token, only that it is quickly
//             * cleared).
//             * 
//             * Note: Conflicts can come from ANY change to the QUORUM znode's
//             * state.
//             */
//            // get a valid zookeeper connection object.
//            final ZooKeeper zk;
//            try {
//                zk = getZookeeper();
//            } catch (InterruptedException e) {
//                // propagate the interrupt.
//                Thread.currentThread().interrupt();
//                return;
//            }
//            while (true) {
//                /*
//                 * Read the current quorum state.
//                 */
//                final Stat stat = new Stat();
//                final QuorumTokenState oldState;
//                try {
//                    oldState = (QuorumTokenState) SerializerUtil.deserialize(zk
//                            .getData(logicalServiceId + "/" + QUORUM,
//                                    false/* watch */, stat));
//                } catch (KeeperException e) {
//                    throw new RuntimeException(e);
//                } catch (InterruptedException e) {
//                    // propagate the interrupt.
//                    Thread.currentThread().interrupt();
//                    return;
//                }
//                /*
//                 * Check some preconditions.
//                 */
//                if (oldState.token() != Quorum.NO_QUORUM) {
//                    /*
//                     * A new value for lastValidToken should not be assigned
//                     * unless the quorum has broken. The quorum token should
//                     * have been cleared when the quorum broken.
//                     */
//                    throw new QuorumException(
//                            "The quorum token has not been cleared");
//                }
//                try {
//                    /*
//                     * Take the new quorum token from the lastValidToken field
//                     * and create a new local state object w/ the current token
//                     * set.
//                     */
//                    final long newToken = oldState.lastValidToken();
//                    final QuorumTokenState newState = new QuorumTokenState(
//                            oldState.lastValidToken(), newToken);
//                    // update data (verifying the version!)
//                    zk.setData(logicalServiceId + "/" + QUORUM, SerializerUtil
//                            .serialize(newState), stat.getVersion());
//                    // done.
//                    if (log.isInfoEnabled())
//                        log.info("Set: token=" + newToken);
//                    return;
//                } catch (BadVersionException e) {
//                    /*
//                     * If we get a version conflict, then just retry. Either the
//                     * token was cleared by someone else or we will try to clear
//                     * it ourselves.
//                     */
//                    log.warn("Concurrent update (retry): serviceId="
//                            + serviceIdStr);
//                    continue;
//                } catch (KeeperException e) {
//                    throw new RuntimeException(e);
//                } catch (InterruptedException e) {
//                    // propagate the interrupt.
//                    Thread.currentThread().interrupt();
//                    return;
//                }
//            }
//        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * This class needs to establish and maintain watchers on the data and/or
     * children or existence of a variety of znodes. Some of those znodes are
     * created and destroyed dynamically, which means that the watchers need to
     * notice those znode creates for some kinds of znodes and establish
     * watchers when those znodes spring into existence.
     * <p>
     * This class maintains {@link Watcher}s which are triggered on the basic
     * zookeeper events (znode existence / data change or znode children child).
     * The watchers simply pump tasks into a queue. When a task on that queue
     * runs, it uses some utility classes to compute the net change between the
     * previous state of the watched collection (members, joined, pipeline,
     * votes for some lastCommitTime) and the current state of that collection.
     * Those deltas are fed back through {@link QuorumWatcherBase} into
     * {@link AbstractQuorum}'s internal state.
     */
    protected class ZkQuorumWatcher extends QuorumWatcherBase {

        /**
         * Service used to process watched events.
         */
        private final AtomicReference<ExecutorService> watcherServiceRef = new AtomicReference<ExecutorService>();
        
        /**
         * @param logicalServiceZPath
         */
        protected ZkQuorumWatcher(final String logicalServiceZPath) {

            super(logicalServiceZPath);

        }

        @Override
        protected void start() {
            
            super.start();

            watcherServiceRef.set(Executors
                    .newSingleThreadExecutor(new DaemonThreadFactory(getClass()
                            .getName())));
            try {

                /*
                 * Setup the quorum state (lazily, eventually consistent).
                 */
                setupQuorum(logicalServiceId, replicationFactor(),
                        getZookeeper(), getZookeeperACL());

                // Setup the watchers.
                setupWatchers(getZookeeper());
                
            } catch (KeeperException e) {
                throw new QuorumException(e);
            } catch (InterruptedException e) {
                // propagate the interrupt.
                Thread.currentThread().interrupt();
                return;
            }

        }

        @Override
        protected void terminate() {
            
            final ExecutorService watcherService = watcherServiceRef.get();

            if (watcherService != null) {
            
                final List<Runnable> notrun = watcherService.shutdownNow();
                for (Runnable r : notrun) {
                    if (r instanceof Future) {
                        ((Future<?>) r).cancel(true/* mayInterruptIfRunning */);
                    }
                }
                
                watcherServiceRef.set(null);
                
            }
            
            super.terminate();
            
        }

        /**
         * Accept notice from {@link ZooKeeper} of a watch event trigger. The
         * {@link InternalQuorumWatcher} will be executed by an internal service
         * to handle the event.
         * 
         * @param watcher
         *            The watcher.
         * @param e
         *            The {@link WatchedEvent}.
         * @param synchronous
         *            When <code>true</code>, the {@link WatchedEvent}will be
         *            handled in the caller's thread (this is used during
         *            startup to ensure that we observe our initial mock events
         *            synchronously.)
         */
        protected void accept(final InternalQuorumWatcher watcher,
                final WatchedEvent e, final boolean synchronous) {

            final Runnable r = new HandleEventRunnable(watcher, e);

            if (synchronous) {

                r.run();

                return;

            }

            final ExecutorService watcherService = watcherServiceRef.get();

            if (watcherService != null) {

                watcherService.execute(r);

            }

        } // accept(...)

        /**
         * Handle event.
         */
        private class HandleEventRunnable implements Runnable {
            
            private final InternalQuorumWatcher watcher;
            private final WatchedEvent e;

            HandleEventRunnable(final InternalQuorumWatcher watcher,
                    final WatchedEvent e) {
                this.watcher = watcher;
                this.e = e;
            }
            
            public void run() {
                try {
                    if (log.isInfoEnabled())
                        log.info(e.toString());
                    switch (e.getState()) {
                    case Disconnected:
                        handleDisconnected();
                        return;
                    case SyncConnected:
                        break;
                    case Expired: {
                        /*
                         * Handle an expired session before we get the
                         * ZooKeeper connection or we can wind up
                         * masquerading the Expired event.
                         */
                        log.error(e);
                        handleExpired();
                    }
                    }
                    /*
                     * Get known valid zookeeper connection.
                     * 
                     * Note: We want the event handler to use a single
                     * ZooKeeper connection instance so an Expired
                     * session will be thrown out rather than
                     * potentially masked.
                     */
                    final ZooKeeper zk = getZookeeper();
                    /*
                     * Handle the event's semantics.
                     */
                    watcher.handleEvent(zk);
                } catch (SessionExpiredException e1) {
                    /*
                     * Handle Expired session thrown out of the watcher.
                     */
                    log.error(e, e1);
                    handleExpired();
                } catch (KeeperException e1) {
                    log.error(e, e1);
//                } catch (InterruptedException e1) {
//                    if (log.isInfoEnabled())
//                        log.info(e1);
                } catch (Throwable e1) {
                    if (InnerCause.isInnerCause(e1, InterruptedException.class)) {
                        /*
                         * Note: This exception probably only occurs through the
                         * shutdown of the ZKQuorumWatcher, which will shutdown
                         * the service handling the event.
                         */
                        if (log.isInfoEnabled())
                            log.info(e1);
                    } else {
                        log.error(e, e1);
                    }
                }
            }
        }
        
        /**
         * Sends out an event notice. Once the ZK session is expired, the quorum
         * can not do any more work. It needs to be terminated and and the
         * start()ed again with a valid ZK session.
         */
        private void handleExpired() {
            log.error("ZOOKEEPER SESSION EXPIRED: token=" + token());
            doNotifyClientDisconnected();
//            /*
//             * Note: We can not cure an expire ZK session. Instead, we tear down
//             * the QuorumClient, obtain a new HClient connectionm, and then
//             * restart the QuorumClient. Therefore this code can not make progress.
//             */
//            while (true) {
//                try {
//                    // wait for a valid ZooKeeper connection.
//                    final ZooKeeper zk = getZookeeper();
//                    // set the watchers.
//                    setupWatchers(zk);
//                    // done.
//                    return;
//                } catch (KeeperException e2) {
//                    log.error(e2, e2);
//                } catch (InterruptedException e2) {
//                    // propagate the interrupt.
//                    Thread.currentThread().interrupt();
//                    return;
//                }
//            }
        }

        /**
         * This service has become disconnected (at the TCP layer) from the
         * zookeeper ensemble - this DOES NOT imply that the client session is
         * expired.
         * <p>
         * Note: The client side of session CAN NOT be expired unless the client
         * is connected. Session expiration is decided by the zookeeper server
         * process, not the client.
         * <p>
         * Note: A {@link ZooKeeper} client connection that has become
         * disconnected will be automatically cured once the client re-connects.
         * When this happens, the watchers will be retriggered (automatically)
         * and the {@link ZKQuorumImpl} will have an opportunity to
         * resynchronize with any state changes in the quorum. THEREFORE, we do
         * not force a session expire or a transition to an error state if the
         * {@link ZooKeeper} client is transiently disconnected (or if the
         * session is moved to another zookeeper server process).
         * <p>
         * There are serveral possible causes of a disconnect:
         * <ul>
         * 
         * <li>zookeeper server process is down.</li>
         * 
         * <li>client can not reach the zookeeper ensemble.</li>
         * 
         * <li>client fails over from one zookeeper server to another (either
         * because the zookeeper server is down or because it has become
         * unreachable).</li>
         * 
         * </ul>
         * 
         * Invoke the {@link QuorumClient#disconnected()} method to allow the
         * client to handle this in an application specific manner.
         */
        private void handleDisconnected() {
            log.warn("ZOOKEEPER CLIENT DISCONNECTED: token=" + token());
            /*
             * Per above, DO NOTHING.
             */
//            doNotifyClientDisconnected();
        }
        
        private void doNotifyClientDisconnected() {
            log.warn("ZOOKEEPER DISCONNECT NOTIFY: token=" + token());
            final QuorumClient<?> client = getClient();
            if (client != null) {
                try {
                    client.disconnected();
                } catch (Exception t) {
                    launderThrowable(t);
                }
            }
            sendEvent(new E(QuorumEventEnum.QUORUM_DISCONNECTED,
                    lastValidToken(), token(), null/* serviceId */));
        }
        
        /**
         * Sets up the watchers and pumps a mock event for each watcher to get
         * things rolling (it causes the data/children of the zpath to be
         * fetched when the mock event is handled, which resets the watcher so
         * things keep moving).
         * 
         * @param zk
         *            A {@link ZooKeeper} connection known to be valid to the
         *            caller.
         * 
         * @throws InterruptedException
         * @throws KeeperException
         */
        private void setupWatchers(final ZooKeeper zk) throws KeeperException,
                InterruptedException {

            /*
             * Notice the token state and its version before we pump the mock
             * events.
             */
            final Stat stat = new Stat();
            final QuorumTokenState tokenState;
            {
                QuorumTokenState tmp;
                try {
                    final byte[] data = getZookeeper().getData(
                            logicalServiceId + "/" + QUORUM, false/* watch */,
                            stat);
                    tmp = (QuorumTokenState) SerializerUtil.deserialize(data);
                } catch (NoNodeException e) {
                    // This is Ok. The node just does not exist yet.
                    tmp = null;
                } catch (KeeperException e) {
                    // Anything else is a problem.
                    throw new RuntimeException(e);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                tokenState = tmp;
            }

            /*
             * Pump mock events to get things started.
             * 
             * Note: These are now synchronous events. This let's us build up a
             * coherent model synchronously within this method. That gives us
             * enough information that we can make a decision about whether or
             * not to clear the quorum token.
             */

            new QuorumMemberWatcher(logicalServiceId + "/" + QUORUM + "/"
                    + QUORUM_MEMBER).start();

            new QuorumPipelineWatcher(logicalServiceId + "/" + QUORUM + "/"
                    + QUORUM_PIPELINE).start();

            new QuorumLastCommitTimesWatcher(logicalServiceId + "/"
                    + QUORUM + "/" + QUORUM_VOTES).start();

            new QuorumJoinedWatcher(logicalServiceId + "/" + QUORUM + "/"
                    + QUORUM_JOINED).start();

            new QuorumTokenStateWatcher(logicalServiceId + "/" + QUORUM)
                    .start();

            if (tokenState != null) {
            
                /*
                 * Conditionally clear the token state if it is provable
                 * based on the quorum reflection that we just assembled
                 * that the quorum is not met.
                 */
                
                conditionalClearQuorumToken(tokenState, stat);
                
            }

        }

        /**
         * Conditionally clear the {@link QuorumTokenState} in zookeeper if the
         * {@link #QUORUM} znode reports a valid token but the quorum is
         * provably not met based on the #of joined services. This is invoked
         * during {@link #setupWatchers(ZooKeeper)}.
         * <p>
         * Note: This code can not run until the watchers have had enough time
         * to discover the current state of the quorum. For this reason, the
         * various start() methods specify synchronous handling the mock
         * {@link WatchedEvent}s. It is safe to handle those mock events in the
         * caller's thread because it is NOT the zookeeper event thread. That
         * also ensures that we have a valid reflection of the state in
         * zookeeper by the time that this method is invoked, so we can make an
         * informed decision about whether or not to clear the quorum token.
         * 
         * TODO I am still a bit uncertain that the initialization logic is
         * correct. {@link AbstractQuorum#start(QuorumClient)} has already
         * invoked
         * {@link ZKQuorumImpl#getLastValidTokenFromQuorumState(QuorumClient)}
         * before we enter our watcher initialization logic and then finally
         * invoke this method to conditionally clear the quorum token. I think
         * that the reflected quorum state will correctly update if we do wind
         * up clearing the token through this mechanism.
         * <p>
         * In order to test this, we need to start 2 out of 3 services, let them
         * meet in a quorum, and then start the third service. It MUST NOT clear
         * the quorum state when it starts. Likewise, if we then abrubtly shut
         * down all of those services (kill them so they do not have a chance to
         * withdraw the durable {@link QuorumStateToken} from zookeeper) and
         * then restart one service, it SHOULD use this logic to clear the token
         * on startup since the quorum is not in fact met (we do not have a bare
         * majority of services that are met). Even then, it is possible that
         * the concurrent start of a bare majority of services could fail to
         * clear the old token with the result that an exception is thrown when
         * we attempt to set the new token when we observe a bare majority of
         * services have joined.
         */
        private void conditionalClearQuorumToken(
                final QuorumTokenState tokenState, final Stat stat) {

            if (tokenState == null)
                throw new IllegalArgumentException();

            if (stat == null)
                throw new IllegalArgumentException();

            try {

                final UUID[] joined = getJoined(); // the joined services.

                if (tokenState.token() != NO_QUORUM && !isQuorum(joined.length)) {

                    try {

                        /*
                         * Replace the QuorumTokenState.
                         * 
                         * There is a durable QuorumTokenState, but it has a
                         * valid token even though the quorum is not met.
                         * 
                         * This can occur on restart since it is quorum clients
                         * that are responsible for managing the state in
                         * zookeeper. If all clients were killed, then none of
                         * them will have had the opportunity to update the
                         * QuorumTokenState in zookeeper and it will falsely
                         * report a valid quorum. We fix that now by replacing
                         * the data in the znode.
                         * 
                         * We only do this if we can prove that the znode
                         * version has not changed since we obtain the
                         * QuorumTokenState.
                         */
                 
                        final QuorumTokenState tmp = new QuorumTokenState(//
                                tokenState.lastValidToken(),// lastValidToken
                                Quorum.NO_QUORUM,// currentToken
                                replicationFactor()//
                        );

                        // update znode unless version is changed.
                        getZookeeper().setData(
                                logicalServiceId + "/" + QUORUM,
                                SerializerUtil.serialize(tmp),
                                stat.getVersion());

                    } catch (BadVersionException ex) {
                        // ignore.
                        if (log.isInfoEnabled())
                            log.info("Version no longer current: " + ex);
                    }

                }

            } catch (KeeperException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

        }

        /**
         * Base class for the zookeeper {@link Watcher}s used by the
         * {@link ZkQuorumWatcher} class. There are several broad categories of
         * watchers: (1) unordered sets (members); (2) ordered sets (pipeline,
         * joined, and votes cast for a specific lastCommitTime); (3) dynamic
         * hierarchy (lastCommitTimes for which there is at least one vote
         * case); and (4) the {@link QuorumTokenState} (the lastValidToken and
         * the current token).
         * <p>
         * The basic actions that an implementation must take are to to
         * re-establish the same watcher when we get the znode or its children,
         * compare the state of the znode/children to the state in the
         * {@link AbstractQuorum}, and then issue any state change messages to
         * bring the {@link AbstractQuorum} into sync. Since we have one watcher
         * per znode / children of interest, this pattern will guarantee that we
         * see all state changes.
         */
        abstract private class InternalQuorumWatcher implements Watcher {

            final String zpath;

            protected InternalQuorumWatcher(final String zpath) {
             
                this.zpath = zpath;
                
            }

            final public void process(final WatchedEvent event) {
                
                accept(this, event, false/* synchronous */);

                if (log.isDebugEnabled())
                    log.debug("zpath=" + zpath + ", event=" + event);

            }

            /**
             * Pump an appropriate mock event into {@link #accept()} to get
             * things moving.
             */
            abstract public void start();

            /**
             * Handle the event using the caller's {@link ZooKeeper} connection
             * (invoked in the application thread).
             * 
             * @param zk
             *            A valid {@link ZooKeeper} connection.
             * 
             * @throws InterruptedException
             * @throws KeeperException
             *             If the {@link ZooKeeper} connection becomes invalid,
             *             throw the {@link KeeperException} out. The caller
             *             will catch it and handle it.
             */
            abstract protected void handleEvent(ZooKeeper zk) throws KeeperException,
                    InterruptedException;
            
        }

        /**
         * A watcher for a set of services (members, pipeline, joined, votes
         * cast for a specific lastCommitTime).
         */
        abstract private class AbstractSetQuorumWatcher extends
                InternalQuorumWatcher {

            protected AbstractSetQuorumWatcher(final String zpath) {

                super(zpath);

            }

            /** pump mock event. */
            public void start() {
                accept(this, new WatchedEvent(
                        Watcher.Event.EventType.NodeChildrenChanged,
                        Watcher.Event.KeeperState.SyncConnected, zpath),//
                        true// synchronous
                );
            }
            
            /**
             * Apply changes in the ordered set of {@link UUID}s, invoking
             * {@link #add(UUID)} or {@link #remove(UUID)} as necessary.
             * 
             * @param aold
             *            The old set (from the internal state of the
             *            {@link AbstractQuorum}).
             * @param anew
             *            The new set (from reading the current state of the
             *            quorum in zookeeper).
             */
            protected void applyOrderedSetSemantics(final UUID[] aold,
                    final UUID[] anew) {
                final OrderedSetDifference<UUID> diff = new OrderedSetDifference<UUID>(
                        aold, anew);
                for (UUID t : diff.removed()) {
                	if (log.isTraceEnabled())
                		log.trace("removing: " + t);
                    remove(t);
                }
                for (UUID t : diff.added()) {
                	if (log.isTraceEnabled())
                		log.trace("adding: " + t);
                    add(t);
                }
            }

            protected void applyUnorderedSetSemantics(final UUID[] aold,
                    final UUID[] anew) {
                final UnorderedSetDifference<UUID> diff = new UnorderedSetDifference<UUID>(
                        aold, anew);
                for (UUID t : diff.removed()) {
                	if (log.isTraceEnabled())
                		log.trace("removing: " + t);
                    remove(t);
                }
                for (UUID t : diff.added()) {
                	if (log.isTraceEnabled())
                		log.trace("adding: " + t);
                    add(t);
                }
            }

            /**
             * Invoked when the service was added to the set.
             */
            abstract protected void add(UUID serviceId);

            /**
             * Invoked when the service was removed from the set.
             */
            abstract protected void remove(UUID serviceId);

        }

        /**
         * A {@link Watcher} for member add and member remove.
         */
        private class QuorumMemberWatcher extends AbstractSetQuorumWatcher {

            QuorumMemberWatcher(final String zpath) {
                super(zpath);
            }

            @Override
            protected void handleEvent(final ZooKeeper zk)
                    throws KeeperException, InterruptedException {
                // get children, resetting the watch as a side-effect.
                final List<String> children = zk.getChildren(zpath, this);
                // the known members.
                final UUID[] aold = getMembers();
                // the current members.
                final UUID[] anew = new UUID[children.size()];
                {
                    int i = 0;
                    for (String s : children) {
                        final String uuidStr = s.substring(
                                QUORUM_MEMBER_PREFIX.length(), s
                                        .length());
                        anew[i++] = UUID.fromString(uuidStr);
                    }
                }
                applyUnorderedSetSemantics(aold, anew);
            }

            @Override
            protected void add(UUID serviceId) {
                memberAdd(serviceId);
            }

            @Override
            protected void remove(UUID serviceId) {
                memberRemove(serviceId);
            }

        } // QuorumMemberWatcher
        
        /**
         * A {@link Watcher} for pipeline add / remove. Unlike the quorum
         * members, order matters for the pipeline.
         */
        private class QuorumPipelineWatcher extends AbstractSetQuorumWatcher {

            QuorumPipelineWatcher(final String zpath) {

                super(zpath);
             
            }

            @Override
            protected void handleEvent(final ZooKeeper zk)
                    throws KeeperException, InterruptedException {
                // get children, resetting the watch as a side-effect.
                final String[] children = zk.getChildren(zpath, this).toArray(
                        new String[0]);
                // lexiographic sort orders children by sequential suffixes.
                Arrays.sort(children);
                // the known pipeline order.
                final UUID[] aold = getPipeline();
                // the new pipeline order.
                final UUID[] anew;
                {
                    /*
                     * Znodes identified above can be concurrently deleted here
                     * so we insert them into a list as we go and then convert
                     * the list to a dense array having the same order as the
                     * children.
                     */
                    final List<UUID> tmp = new LinkedList<UUID>();
                    for (String s : children) {
                        try {
                            final QuorumPipelineState state = (QuorumPipelineState) SerializerUtil
                                    .deserialize(zk.getData(zpath + "/" + s,
                                            false/* watch */, null/* stat */));
                            tmp.add(state.serviceUUID());
                        } catch (NoNodeException ex) {
                            /*
                             * Concurrent delete.
                             * 
                             * Note: Since the concurrent delete, by definition,
                             * occurred after we get the children, we will see
                             * another watch trigger with this change soon.
                             */
                            continue;
                        }
                    }
                    anew = tmp.toArray(new UUID[0]);
                }
                applyOrderedSetSemantics(aold, anew);
            }

            @Override
            protected void add(UUID serviceId) {
                pipelineAdd(serviceId);
            }

            @Override
            protected void remove(UUID serviceId) {
                pipelineRemove(serviceId);
            }

        } // QuorumPipelineWatcher

        /**
         * A {@link Watcher} for service join/leave. Unlike the quorum members,
         * order matters for the joined services.
         */
        private class QuorumJoinedWatcher extends AbstractSetQuorumWatcher {

            QuorumJoinedWatcher(final String zpath) {
                super(zpath);
            }

            @Override
            protected void handleEvent(final ZooKeeper zk)
                    throws KeeperException, InterruptedException {
                // get children, resetting the watch as a side-effect.
                final String[] children = zk.getChildren(zpath, this).toArray(
                        new String[0]);
                // lexiographic sort orders children by sequential suffixes.
                Arrays.sort(children);
                // the known join order.
                final UUID[] aold = getJoined();
                // the new join order.
                final UUID[] anew;
                {
                    /*
                     * Znodes identified above can be concurrently deleted here
                     * so we insert them into a list as we go and then convert
                     * the list to a dense array having the same order as the
                     * children.
                     */
                    final List<UUID> tmp = new LinkedList<UUID>();
                    for (String s : children) {
                        try {
                            final QuorumServiceState state = (QuorumServiceState) SerializerUtil
                                    .deserialize(zk.getData(zpath + "/" + s,
                                            false/* watch */, null/* stat */));
                            tmp.add(state.serviceUUID());
                        } catch(NoNodeException ex) {
                            // concurrent delete.
                            continue;
                        }
                    }
                    anew = tmp.toArray(new UUID[0]);
                }
                applyOrderedSetSemantics(aold, anew);
            }

            @Override
            protected void add(UUID serviceId) {
                serviceJoin(serviceId);
            }

            @Override
            protected void remove(UUID serviceId) {
                serviceLeave(serviceId);
            }

        } // QuorumJoinedWatcher

        /**
         * A {@link Watcher} for the {@link QuorumTokenState} on the
         * {@link ZKQuorum#QUORUM} znode.
         */
        private class QuorumTokenStateWatcher extends InternalQuorumWatcher {

            protected QuorumTokenStateWatcher(final String zpath) {
                super(zpath);
            }

            /** pump mock event. */
            public void start() {
                accept(this, new WatchedEvent(
                        Watcher.Event.EventType.NodeChildrenChanged,
                        Watcher.Event.KeeperState.SyncConnected, zpath),//
                        true// synchronous
                );
            }
            
            @Override
            protected void handleEvent(final ZooKeeper zk) throws KeeperException,
                    InterruptedException {

                // get the current state, resetting the watch.
                final QuorumTokenState state = (QuorumTokenState) SerializerUtil
                        .deserialize(zk.getData(zpath, this, null/* stat */));

                lock.lock();
                try {
                    final long tok = token();
                    if (state.token() == NO_QUORUM && tok != NO_QUORUM) {
                        clearToken();
                    } else if (lastValidToken() != state.lastValidToken()
                            || token() != state.token()) {
                        final UUID[] joined = getJoined();
                        final int k = replicationFactor();
                        if (isQuorum(joined.length)) {
                            setToken(state.lastValidToken());
                        } else {
                            log.warn("Can not set token - not enough joined services: k="
                                    + k + ", joined=" + joined.length);
                        }
                    } else {
                        if (log.isInfoEnabled())
                            log.info("Nothing to do: currentState=" + state
                                    + ", token()=" + tok);
                    }
                } finally {
                    lock.unlock();
                }
                
            }
            
        } // QuorumTokenStateWatcher

        /**
         * A {@link Watcher} for distinct lastCommitTimes for which there are
         * votes case which additionally imposes a watcher on the ordered votes
         * cast for each such lastCommitTime.
         * 
         * @see QuorumVotesWatcher
         */
        private class QuorumLastCommitTimesWatcher extends InternalQuorumWatcher {

            QuorumLastCommitTimesWatcher(final String zpath) {

                super(zpath);
             
            }

            /**
             * Pump mock event and setup a watcher for any lastCommitTime
             * currently believed to exist by the local quorum object.
             */
            public void start() {
                
                accept(this, new WatchedEvent(
                        Watcher.Event.EventType.NodeChildrenChanged,
                        Watcher.Event.KeeperState.SyncConnected, zpath),//
                        true// synchronous
                );

                /*
                 * Setup a watcher for any lastCommitTime thought to exist
                 * within the local copy of the distributed quorum state
                 * maintained by AbstractQuorum.
                 */

                final Long[] lastCommitTimes = getVotes().keySet().toArray(
                        new Long[0]);

                for (Long lastCommitTime : lastCommitTimes) {

                    // setup watcher and pump mock event.
                    new QuorumVotesWatcher(zpath + "/" + lastCommitTime,
                            lastCommitTime).start();

                }
                
            }

            @Override
            protected void handleEvent(final ZooKeeper zk) throws KeeperException,
                    InterruptedException {
                // get children, resetting the watch as a side-effect.
                final String[] children = zk.getChildren(zpath,
                        this).toArray(new String[0]);
                // the current votes.
                final Map<Long, UUID[]> votes = getVotes();
                // the known lastCommitTimes.
                final Long[] aold = votes.keySet().toArray(new Long[0]);
                // the new lastCommitTimes.
                final Long[] anew = new Long[children.length];
                {
                    int i = 0;
                    for (String s : children) {
                        anew[i++] = Long.valueOf(s);
                    }
                }
                // compute the change sets.
                final UnorderedSetDifference<Long> diff = new UnorderedSetDifference<Long>(
                        aold, anew);
                /*
                 * Clear any votes for lastCommitTimes which are no longer in
                 * use.
                 * 
                 * @todo unit test for this.
                 */
                for(Long t : diff.removed()) {
                    final UUID[] a = votes.get(t);
                    for(UUID x : a) {
                        withdrawVote(x);
                    }
                }
                /*
                 * Add watchers for any new lastCommitTimes. They will detect
                 * any new votes cast when the pump a mock event in start().
                 */
                for (Long t : diff.added()) {
                    new QuorumVotesWatcher(zpath + "/" + t, t).start();
                }
            }

        } // QuorumLastCommitTimesWatcher

        /**
         * Watcher for the ordered set of services casting their vote for a
         * specific lastCommitTime.
         * 
         * @see QuorumLastCommitTimesWatcher
         */
        private class QuorumVotesWatcher extends AbstractSetQuorumWatcher {

            private final Long lastCommitTime;

            QuorumVotesWatcher(final String zpath, final Long lastCommitTime) {

                super(zpath);

                if (lastCommitTime == null)
                    throw new IllegalArgumentException();

                this.lastCommitTime = lastCommitTime;
                
            }

            @Override
            protected void handleEvent(final ZooKeeper zk) throws KeeperException,
                    InterruptedException {
                // get children, resetting the watch as a side-effect.
                final String[] children;
                try {
                    children = zk.getChildren(zpath, this).toArray(
                            new String[0]);
                } catch (NoNodeException ex) {
                    /*
                     * Note: This exception can arise if some thread withdraws
                     * the services vote leaving no other votes for the given
                     * lastCommitTime. The ZkQuorumActor will go ahead and
                     * delete the zpath for that lastCommitTime as so as there
                     * are no children remaining, which causes the
                     * NoNodeException here when we look for those children.
                     * This is not a problem and we ignore this exception.
                     */
                    if (log.isInfoEnabled())
                        log.info("No votes remain: lastCommitTime="
                                + lastCommitTime + ", zpath=" + zpath);
                    return;
                }
                // lexiographic sort orders children by sequential suffixes.
                Arrays.sort(children);
                final Map<Long, UUID[]> votes = getVotes();
                /*
                 * The known vote order and an empty UUID[] if there are no
                 * votes cast for that lastCommitTime.
                 */
                final UUID[] aold = votes.containsKey(lastCommitTime) ? votes
                        .get(lastCommitTime) : new UUID[0];
                // the new vote order.
                final UUID[] anew;
                {
                    /*
                     * Znodes identified above can be concurrently deleted here
                     * so we insert them into a list as we go and then convert
                     * the list to a dense array having the same order as the
                     * children.
                     */
                    final List<UUID> tmp = new LinkedList<UUID>();
                    for (String s : children) {
                        try {
                            final QuorumServiceState state = (QuorumServiceState) SerializerUtil
                                    .deserialize(zk.getData(zpath + "/" + s,
                                            false/* watch */, null/* stat */));
                            tmp.add(state.serviceUUID());
                        } catch(NoNodeException ex) {
                            // concurrent delete.
                            continue;
                        }
                    }
                    anew = tmp.toArray(new UUID[0]);
                }
                applyOrderedSetSemantics(aold, anew);
            }

            @Override
            protected void add(final UUID serviceId) {
                castVote(serviceId, lastCommitTime);
            }

            @Override
            protected void remove(final UUID serviceId) {
                withdrawVote(serviceId);
            }

        } // QuorumVotesWatcher

    } // ZkQuorumWatcher

    /**
     * Since the QourumTokenState exists, we need to initialize the
     * lastValidToken from the QuorumTokenState in case the quorum has
     * met in the past.
     */
    @Override
    protected long getLastValidTokenFromQuorumState(final C client) {
//      super.getLastValidTokenFromQuorumState(client);
        final String logicalServiceZPath = client.getLogicalServiceZPath();
        final String quorumZPath = logicalServiceZPath + "/" + QUORUM;
        while (true) {
            final byte[] data;
            try {

                final Stat stat = new Stat();

                data = getZookeeper().getData(quorumZPath,
                        false/* watch */, stat);

                final QuorumTokenState tokenState = (QuorumTokenState) SerializerUtil
                        .deserialize(data);

                if (log.isInfoEnabled())
                    log.info("Starting with quorum that has already met in the past: "
                            + tokenState);

                if (tokenState.replicationFactor() == 0
                        || tokenState.replicationFactor() != replicationFactor()) {

                    /*
                     * Either a data migration (the replication factor was not
                     * originally in the QuorumTokenState) -OR- a change in the
                     * replication factor for the HA cluster.
                     */
                    if (tokenState.token() != Quorum.NO_QUORUM) {

                        /*
                         * We must not change the replication factor of a quorum
                         * that is already met (in fact, we should not change it
                         * if there are any running HAGlue instances).
                         */
                        
                        throw new QuorumException(
                                "Can not change replication factor of a met quorum: newValue="
                                        + replicationFactor() + ", oldValue="
                                        + +tokenState.replicationFactor());

                    }
                    
                    /*
                     * Note: This detects a concurrent update and retries so the
                     * [lastValidToken] returned below is known to be consistent
                     * with the state of this znode at the time that it was
                     * updated.
                     */

                    try {

                        updateQuorumStateWithReplicationFactor(client,
                                quorumZPath, tokenState/* oldState */, stat);

                    } catch (BadVersionException e) {
                        /*
                         * If we get a version conflict, then just retry. Could
                         * have been concurrently touched by another service.
                         */
                        log.warn("Concurrent update (retry): zpath="
                                + quorumZPath);
                        continue;
                    }

                }

                return tokenState.lastValidToken();
            } catch (NoNodeException e) {
                // This is Ok. The node just does not exist yet.
                return NO_QUORUM;
            } catch (KeeperException e) {
                // Anything else is a problem.
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Migrate the {@link QuorumTokenState} to include the replicationFactor.
     * The replicationFactor is specified to the constructor and is taken from
     * {@link #replicationFactor()}. This imposes that replicationFactor onto
     * the {@link QuorumTokenState}. This is used both for data migration (the
     * replication factor was not originally in the {@link QuorumTokenState})
     * and to change the replication factor when it is discovered to be changed
     * after a quorum restart.
     * 
     * @throws BadVersionException
     *             if the operation was not performed because the caller's
     *             version information was no longer valid. The caller should
     *             retry.
     */
    private void updateQuorumStateWithReplicationFactor(final C client,
            final String quorumZPath, final QuorumTokenState oldState,
            final Stat stat) throws BadVersionException {
        final QuorumTokenState newState = new QuorumTokenState(
                oldState.lastValidToken(), oldState.token(),
                replicationFactor());
        try {
            getZookeeper().setData(quorumZPath,
                    SerializerUtil.serialize(newState), stat.getVersion());
            // done.
            log.warn("Set replicationFactor: zpath=" + quorumZPath
                    + ", newState=" + newState);
            return;
        } catch (BadVersionException ex) {
            throw (BadVersionException) ex;
        } catch (KeeperException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            // propagate the interrupt.
            Thread.currentThread().interrupt();
            return;
        }
    }
    
    /**
     * Ensure that the zpaths for the {@link BigdataZooDefs#QUORUM} and its
     * various persistent children exist.
     * 
     * @param logicalServiceZPath
     *            The fully qualified logical service zpath.
     * @param replicationFactor
     *            The replication factor for the quorum (must be a non-negative,
     *            odd integer).
     * @param zk
     *            The {@link ZooKeeper} client connection
     * @param acl
     *            The ACLs.
     * 
     * @throws KeeperException
     * @throws InterruptedException
     */
    static public void setupQuorum(final String logicalServiceZPath,
            final int replicationFactor, final ZooKeeper zk, final List<ACL> acl)
            throws KeeperException, InterruptedException {

        /*
         * Note: This does not work out. We do not always create a Quorum with
         * the same length zpath prefix, at least not in the test suite.
         */
//        /*
//         * Ensure key znodes exist.
//         * 
//         * Note: This breaks down the given logicalServiceZPath into components
//         * and then builds them up to ensure that the necessary path components
//         * exist.
//         */
//        {
//            final String[] a = logicalServiceZPath.split("/");
//            if (a.length != 4)
//                throw new IllegalArgumentException(
//                        "Bad zpath? logicalServiceZPath=" + logicalServiceZPath);
//            final String zroot = a[1];
//            final String logicalServiceZPathPrefix = zroot + "/" + a[2];
////            final String logicalServiceIdentifier = a[3]; // ignore. full length.
//            try {
//                zk.create(zroot, new byte[] {/* data */}, acl,
//                        CreateMode.PERSISTENT);
//            } catch (NodeExistsException ex) {
//                // ignore.
//            }
//            try {
//                zk.create(logicalServiceZPathPrefix, new byte[] {/* data */},
//                        acl, CreateMode.PERSISTENT);
//            } catch (NodeExistsException ex) {
//                // ignore.
//            }
//            try {
//                zk.create(logicalServiceZPath, new byte[] {/* data */}, acl,
//                        CreateMode.PERSISTENT);
//            } catch (NodeExistsException ex) {
//                // ignore.
//            }
//        }

        try {
            final QuorumTokenState tokenState = new QuorumTokenState(//
                    Quorum.NO_QUORUM,// lastValidToken
                    Quorum.NO_QUORUM,// currentToken
                    replicationFactor// replicationFactor
            );
            zk.create(logicalServiceZPath + "/" + QUORUM,
                    SerializerUtil.serialize(tokenState), acl,
                    CreateMode.PERSISTENT);
        } catch (NodeExistsException ex) {
            // ignore.
        }

        try {
            zk.create(
                    logicalServiceZPath + "/" + QUORUM + "/" + QUORUM_MEMBER,
                    new byte[0]/* empty */, acl, CreateMode.PERSISTENT);
        } catch (NodeExistsException ex) {
            // ignore.
        }

        try {
            zk.create(
                    logicalServiceZPath + "/" + QUORUM + "/" + QUORUM_VOTES,
                    new byte[0]/* empty */, acl, CreateMode.PERSISTENT);
        } catch (NodeExistsException ex) {
            // ignore.
        }

        try {
            zk.create(
                    logicalServiceZPath + "/" + QUORUM + "/" + QUORUM_JOINED,
                    new byte[0]/* empty */, acl, CreateMode.PERSISTENT);
        } catch (NodeExistsException ex) {
            // ignore.
        }

        try {
            zk.create(
                    logicalServiceZPath + "/" + QUORUM + "/" + QUORUM_PIPELINE,
                    new byte[0]/* empty */, acl, CreateMode.PERSISTENT);
        } catch (NodeExistsException ex) {
            // ignore.
        }

//        return tokenState;
        
    }
    
    /**
     * Compares string values representing <code>lastCommitTime</code> votes by
     * decoding them to <code>long</code> values.
     */
    private static class VoteComparator implements Comparator<String> {

        final static VoteComparator INSTANCE = new VoteComparator();

        @Override
        public int compare(final String arg0, final String arg1) {
            final long v0 = Long.parseLong(arg0);
            final long v1 = Long.parseLong(arg1);
            if (v0 < v1)
                return -1;
            if (v0 > v1)
                return 1;
            return 0;
        }

    }

}
