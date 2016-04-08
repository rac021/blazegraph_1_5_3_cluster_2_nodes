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
 * Created on Jan 16, 2009
 */

package com.bigdata.service.jini.master;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import net.jini.config.Configuration;
import net.jini.config.ConfigurationException;
import net.jini.core.lookup.ServiceItem;
import net.jini.core.lookup.ServiceTemplate;

import org.apache.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.data.Stat;

import com.bigdata.counters.CounterSet;
import com.bigdata.io.SerializerUtil;
import com.bigdata.jini.start.BigdataZooDefs;
import com.bigdata.service.AbstractScaleOutFederation;
import com.bigdata.service.DataService;
import com.bigdata.service.IBigdataFederation;
import com.bigdata.service.IClientService;
import com.bigdata.service.IDataService;
import com.bigdata.service.IDataServiceCallable;
import com.bigdata.service.IMetadataService;
import com.bigdata.service.IRemoteExecutor;
import com.bigdata.service.jini.JiniFederation;
import com.bigdata.service.jini.util.DumpFederation.ScheduledDumpTask;
import com.bigdata.service.ndx.pipeline.KVOLatch;
import com.bigdata.util.concurrent.ExecutionExceptions;
import com.bigdata.zookeeper.ZLock;
import com.bigdata.zookeeper.ZLockImpl;
import com.bigdata.zookeeper.ZooHelper;

/**
 * Utility class that can be used to execute a distributed job. The master
 * creates a set of tasks, submits each task to an {@link IDataService} for
 * execution, and awaits their {@link Future}s. There are a variety of
 * {@link ConfigurationOptions}. In order to execute a master, you specify a
 * concrete instance of this class and {@link ConfigurationOptions} using the
 * fully qualified class name of that master implementation class. You specify
 * the client task using {@link TaskMaster#newClientTask(int)}.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 * @param <S>
 *            The generic type of the {@link JobState}.
 * @param <T>
 *            The generic type of the client task.
 * @param <U>
 *            The generic type of the value returned by the client task.
 * 
 * @todo could refactor the task to a task sequence easily enough, perhaps using
 *       some of the rule step logic. That would be an interesting twist on a
 *       parallel datalog.
 */
abstract public class TaskMaster<S extends TaskMaster.JobState, T extends Callable<U>, U>
        implements Callable<Void> {

    final protected static Logger log = Logger.getLogger(TaskMaster.class);

    /**
     * {@link Configuration} options for the {@link TaskMaster} and derived
     * classes. The "component" for these options is the name of the concrete
     * master class to be executed.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     * @version $Id$
     */
    public interface ConfigurationOptions {

		/**
		 * When <code>true</code> as an after action on the job, the
		 * {@link DataService}s in the federation will be made to undergo
		 * asynchronous overflow processing, a compacting merge will be
		 * requested for all shards, and the live journals will be truncated so
		 * that the total size on disk of the federation is at its minimum
		 * footprint for the given history retention policy (default
		 * <code>false</code>). The master will block during this operation so
		 * you can readily tell when it is finished. Note that this option only
		 * makes sense in benchmark environments where you can control the total
		 * system otherwise asynchronous writes may continue.
		 * 
		 * @see AbstractScaleOutFederation#forceOverflow(boolean, boolean)
		 */
        String FORCE_OVERFLOW = "forceOverflow";

        /**
         * The path to the directory in where {@link ScheduledDumpTask}s will
         * write metadata about the state, size, and other aspects of the index
         * partitions throughout the run (optional).
         * 
         * @see #INDEX_DUMP_NAMESPACE
         */
        String INDEX_DUMP_DIR = "indexDumpDir";

        /**
         * The namespace to be used for the {@link ScheduledDumpTask}s
         * (optional).
         * 
         * @see #INDEX_DUMP_DIR
         */
        String INDEX_DUMP_NAMESPACE = "indexDumpNamespace";

        /**
         * Boolean option may be used to delete the exiting job with the same
         * name during startup (default <code>false</code>). This can be used
         * if the last job terminated abnormally and you want to re-run the job.
         */
        String DELETE_JOB = "deleteJob";

        /**
         * The #of clients to start. The clients will be distributed across the
         * discovered {@link IRemoteExecutor}s in the federation matching the
         * {@link #CLIENTS_TEMPLATE}.
         */
        String NCLIENTS = "nclients";

        /**
         * A {@link ServicesTemplate} describing the types of services, and the
         * minimum #of services of each type, to which the clients will be
         * submitted for execution.
         * <p>
         * These services MUST implement {@link IRemoteExecutor} since that is
         * that API which will be used to submit the client tasks for execution.
         * Normally, you will specify {@link IClientService} as the required
         * interface. While it is also possible to run clients on an
         * {@link IDataService} or even an {@link IMetadataService}, that is
         * discouraged except when the tasks require local access to resources
         * hosted by the service - for example, an administrative task requiring
         * access to the index partitions locally on each {@link IDataService}.
         * 
         * @see #NCLIENTS
         */
        String CLIENTS_TEMPLATE = "clientsTemplate";

        /**
         * The #of aggregators to start (default is ZERO(0)). The aggregators
         * will be distributed across the discovered {@link IRemoteExecutor}s
         * in the federation matching the {@link #AGGREGATORS_TEMPLATE}.
         * 
         * @see #AGGREGATORS_TEMPLATE
         * 
         * @deprecated This is a trial feature which is not fully implemented.
         */
        String NAGGREGATORS = "naggregators";

        /**
         * A {@link ServiceTemplate} describing the types of services, and the
         * minimum #of services, on which aggregation for asynchronous index
         * writes will be performed (default is <code>null</code>, which
         * means that aggregators will not be discovered).
         * <p>
         * The aggregator plays a role similar to the "reduce" of a map/reduce
         * architecture. However, unlike map/reduce, an aggregator does not
         * fully buffer the output set of the clients. Instead, each aggregator
         * combines asynchronous index partition writes from multiple clients,
         * splits those writes based on the current index partitions, and
         * buffers chunks destined for each index partition until either the
         * chunk size or the chunk timeout has been satisfied, at which point
         * the chunk is written onto the corresponding index partition.
         * <p>
         * An aggregation step is necessary when there are a large #of index
         * partitions for some index. Without an aggregator, each client will
         * attempt to fill a chunk destined for each index partition. As the #of
         * index partitions increases, clients can run at 100% CPU utilization
         * trying to fill those chunks. When this occurs, the client is at the
         * single machine limit.
         * <p>
         * By introducing an aggregation step, the client writes on a buffer
         * which is drained by a thread writing onto the specified
         * aggregator(s). This allows many more clients to run when compared
         * with the #of services buffering chunks and performing the index
         * writes. By decomposing the production and buffering stages we are
         * able to get around the single machine limit.
         * <p>
         * Aggregators are essentially specialized clients and may execute in
         * any {@link IClientService} container. They may be restricted to
         * execute on only those services having specific attributes using this
         * template.
         * 
         * @see #NAGGREGATORS
         * 
         * @todo #of aggregators per index.
         * 
         * @todo Each aggregator can be its own service so each index could be
         *       aggregated by a different aggregator on a different host.
         *       <p>
         *       Aggregator failure requires either restart of the job or
         *       re-processing of all source "documents" whose write set has not
         *       yet been made restart safe. In order to track that, we need to
         *       use a proxy for a {@link KVOLatch} for scale-out index for each
         *       document processed. When the write set for a scale-out index
         *       for that document is complete, the latch is triggered and the
         *       client is notified.
         * 
         * @deprecated This is a trial feature which is not fully implemented.
         */
        String AGGREGATORS_TEMPLATE = "aggregatorsTemplate";

        /**
         * An array of zero or more {@link ServicesTemplate} describing the
         * types of services, and the minimum #of services of each type, that
         * must be discovered before the job may begin.
         */
        String SERVICES_TEMPLATES = "servicesTemplates";

        /**
         * The timeout in milliseconds to await the discovery of the various
         * services described by the {@link #SERVICES_TEMPLATES} and
         * {@link #CLIENTS_TEMPLATE}.
         */
        String SERVICES_DISCOVERY_TIMEOUT = "awaitServicesTimeout";

        /**
         * The job name is used to identify the job within zookeeper. A znode
         * with this name will be created as follows:
         * 
         * <pre>
         * zroot (of the federation)
         *    / jobs
         *      / TaskMaster (fully qualified name of the concrete master class).
         *        / jobName
         * </pre>
         * 
         * If the client will store state in zookeeper or use {@link ZLock}s,
         * it must create a znode under the jobName whose name is the assigned
         * <em>client#</em>. This znode may be used by the client to store
         * its state in zookeeper. The client may also create {@link ZLock}s
         * which are children of this znode.
         * 
         * <pre>
         *          / client# (where # is the client#; the data of this znode is typically the client's state).
         *            / locknode (used to elect the client that is running if there is contention).
         *            / ...
         * </pre>
         * 
         * @see JobState#getClientZPath(JiniFederation, int)
         */
        String JOB_NAME = "jobName";

    }

    /**
     * State describing the job to be executed. The various properties are all
     * defined by {@link ConfigurationOptions}.
     */
    public static class JobState implements Serializable {

        /**
         * 
         */
        private static final long serialVersionUID = -340273551639560974L;

        /**
         * Set to <code>true</code> iff a pre-existing instance of the same
         * job should be delete before starting this job.
         * 
         * @see ConfigurationOptions#DELETE_JOB
         */
        /*private*/ transient final boolean deleteJob;

        /**
         * Set <code>true</code> iff an existing job is being resumed
         * (defaults to <code>false</code> until proven otherwise).
         */
        /*private*/ boolean resumedJob = false;

        /**
         * Return <code>true</code> iff an existing job is being resumed.
         */
        public boolean isResumedJob() {

            return resumedJob;

        }

        /**
         * The time at which the job started to execute and 0L if the job has
         * not started to execute.
         */
        /*private*/ transient long beginMillis = 0L;

        /**
         * The time at which the job was done executing and 0L if the job has
         * not finished executing.
         */
        /*private*/ transient long endMillis = 0L;

        /**
         * Elapsed run time for the job in milliseconds. This is ZERO (0L) until
         * the job starts. Once the job is done executing the elapsed time will
         * no longer increase.
         */
        public long getElapsedMillis() {

            if (beginMillis == 0L)
                return 0L;

            if (endMillis == 0L) {

                return System.currentTimeMillis() - beginMillis;

            }

            return endMillis - beginMillis;

        }

        /**
         * A map giving the {@link Future} for each client. The keys of the map
         * are the client numbers in [0:N-1].
         * 
         * @see #startClients()
         * @see #awaitAll(Map)
         * @see #cancelAll(Map, boolean)
         */
        protected transient Map<Integer/* client# */, Future<?/* U */>> futures;

        /*
         * Public options and configuration information.
         */

        /**
         * The name <em>component</em> in the jini {@link Configuration} whose
         * values will be used to configure the {@link JobState}. This defaults
         * to the name of the concrete {@link TaskMaster} instance. You may
         * override this value using <code>-Dbigdata.component=foo</code> on
         * the command line. This makes it possible to have multiple
         * parameterizations for the same master class in a single
         * {@link Configuration} file.
         */
        public final String component;

        /**
         * The job name.
         * 
         * @see ConfigurationOptions#JOB_NAME
         */
        public final String jobName;

        /**
         * The #of client tasks.
         * 
         * @see ConfigurationOptions#NCLIENTS
         */
        public final int nclients;

        /**
         * The {@link ServicesTemplate} describing the types of services and the
         * minimum #of services to which the clients will be distributed for
         * remote execution.
         * 
         * @see ConfigurationOptions#CLIENTS_TEMPLATE
         */
        public final ServicesTemplate clientsTemplate;

        /**
         * The #of aggregator tasks.
         * 
         * @see ConfigurationOptions#NAGGREGATORS
         */
        public final int naggregators;

        /**
         * The {@link ServicesTemplate} describing the types of services and the
         * minimum #of services for aggregating asynchronous index writes
         * performed by the clients.
         * 
         * @see ConfigurationOptions#AGGREGATORS_TEMPLATE
         */
        public final ServicesTemplate aggregatorsTemplate;

        /**
         * An array of zero or more {@link ServicesTemplate} describing the
         * types of services, and the minimum #of services of each type, that
         * must be discovered before the job may begin.
         * 
         * @see ConfigurationOptions#SERVICES_TEMPLATES
         */
        public final ServicesTemplate[] servicesTemplates;

        /**
         * @see ConfigurationOptions#AWAIT_SERVICES_TIMEOUT}
         */
        public final long servicesDiscoveryTimeout;

        /*
         * Debugging and benchmarking options.
         */

        /**
         * <code>true</code> iff overflow will be forced on the data services
         * after the client tasks are done.
         * 
         * @see ConfigurationOptions#FORCE_OVERFLOW
         */
        public final boolean forceOverflow;

        /**
         * The directory into which scheduled dumps of the index partition
         * metadata will be written by a {@link ScheduledDumpTask} (optional).
         * 
         * @see ConfigurationOptions#INDEX_DUMP_DIR
         */
        public final File indexDumpDir;

        /**
         * The namespace to be used for the scheduled dumps of the index
         * partition metadata (optional).
         * 
         * @see ConfigurationOptions#INDEX_DUMP_NAMESPACE
         */
        public final String indexDumpNamespace;

        /**
         * Allows extension of {@link #toString()}
         * 
         * @param sb
         */
        protected void toString(StringBuilder sb) {

        }

        public String toString() {

            final StringBuilder sb = new StringBuilder();

            sb.append(getClass().getName());

            sb.append("{ resumedJob=" + isResumedJob());

            /*
             * General options.
             */

            sb.append(", component=" + component);

            sb.append(", " + ConfigurationOptions.JOB_NAME + "=" + jobName);

            sb.append(", " + ConfigurationOptions.NCLIENTS + "=" + nclients);

//            sb.append(", " + ConfigurationOptions.NAGGREGATORS + "="
//                    + naggregators);

            sb.append(", " + ConfigurationOptions.CLIENTS_TEMPLATE + "="
                    + clientsTemplate);

//            sb.append(", " + ConfigurationOptions.AGGREGATORS_TEMPLATE + "="
//                    + aggregatorsTemplate);

            sb.append(", " + ConfigurationOptions.SERVICES_TEMPLATES + "="
                    + Arrays.toString(servicesTemplates));

            sb.append(", " + ConfigurationOptions.SERVICES_DISCOVERY_TIMEOUT
                    + "=" + servicesDiscoveryTimeout);

            /*
             * Debugging and benchmarking options.
             */

            sb.append(", " + ConfigurationOptions.FORCE_OVERFLOW + "="
                    + forceOverflow);

            /*
             * Run state stuff.
             */

            /*
             * Subclass's options.
             */
            toString(sb);

            sb.append("}");

            return sb.toString();

        }

        protected JobState(final String component, final Configuration config)
                throws ConfigurationException {

            if (component == null)
                throw new IllegalArgumentException();

            if (config == null)
                throw new IllegalArgumentException();

            this.component = component;

            /*
             * general options.
             */

            jobName = (String) config.getEntry(component,
                    ConfigurationOptions.JOB_NAME, String.class);

            deleteJob = (Boolean) config.getEntry(component,
                    ConfigurationOptions.DELETE_JOB, Boolean.TYPE,
                    Boolean.FALSE);

            nclients = (Integer) config.getEntry(component,
                    ConfigurationOptions.NCLIENTS, Integer.TYPE);

            naggregators = (Integer) config
                    .getEntry(component, ConfigurationOptions.NAGGREGATORS,
                            Integer.TYPE, 0/* default */);

            clientsTemplate = (ServicesTemplate) config.getEntry(component,
                    ConfigurationOptions.CLIENTS_TEMPLATE,
                    ServicesTemplate.class);

            aggregatorsTemplate = (ServicesTemplate) config.getEntry(component,
                    ConfigurationOptions.AGGREGATORS_TEMPLATE,
                    ServicesTemplate.class, null/* default */);

            servicesTemplates = (ServicesTemplate[]) config.getEntry(component,
                    ConfigurationOptions.SERVICES_TEMPLATES,
                    ServicesTemplate[].class);

            servicesDiscoveryTimeout = (Long) config.getEntry(component,
                    ConfigurationOptions.SERVICES_DISCOVERY_TIMEOUT, Long.TYPE);

            /*
             * Benchmarking and debugging options.
             */

            forceOverflow = (Boolean) config.getEntry(component,
                    ConfigurationOptions.FORCE_OVERFLOW, Boolean.TYPE,
                    Boolean.FALSE);

            indexDumpDir = (File) config.getEntry(component,
                    ConfigurationOptions.INDEX_DUMP_DIR, File.class, null);

            indexDumpNamespace = (String) config.getEntry(component,
                    ConfigurationOptions.INDEX_DUMP_NAMESPACE, String.class,
                    null);

            /*
             * Client/service maps.
             */

            clientServiceMap = new ServiceMap(nclients);

            /*
             * Aggregator/service maps.
             */

            aggregatorServiceMap = new ServiceMap(naggregators);

        }

        /**
         * The mapping of clients onto the {@link IRemoteExecutor}s on which
         * that client will execute.
         */
        final public ServiceMap clientServiceMap;

        /**
         * The mapping of aggregators onto the {@link IRemoteExecutor}s on
         * which that aggregator will execute.
         */
        final public ServiceMap aggregatorServiceMap;

        /**
         * Return the zpath of the node for all jobs which are instances of the
         * configured master's class.
         * 
         * @see #component
         */
        final public String getJobClassZPath(final JiniFederation fed) {

            return fed.getZooConfig().zroot + "/" + BigdataZooDefs.JOBS + "/"
                    + component;

        }

        /**
         * Return the zpath to the znode which corresponds to the job which is
         * being executed. The data for this znode is this {@link JobState}.
         */
        final public String getJobZPath(final JiniFederation fed) {

            return getJobClassZPath(fed) + "/" + jobName;

        }

        /**
         * Return the zpath to the node which corresponds to the specified
         * client task. This znode is a direct child of the znode for the job.
         * The client is responsible for creating this zpath if they wish to
         * store state in zookeeper. Any {@link ZLock}s used by the client and
         * scoped to its work should be created as children of this zpath.
         * 
         * @param clientNum
         *            The client number.
         * 
         * @see ConfigurationOptions#JOB_NAME
         */
        final public String getClientZPath(final JiniFederation fed,
                final int clientNum) {

            return getJobZPath(fed) + "/" + "client" + clientNum;

        }

        /**
         * Return the zpath of the locknode for the specified client task. Any
         * tasks running with that clientNum MUST contend for a {@link ZLock}
         * which permits it to run the task. This prevents concurrent execution
         * of the task for the specified client in the event that more than one
         * master is running for the same {@link JobState}.
         * 
         * @param clientNum
         *            The client number.
         */
        final public String getLockNodeZPath(final JiniFederation fed,
                final int clientNum) {

            return getClientZPath(fed, clientNum) + "/" + "locknode";

        }

    }

    /**
     * The federation (from the ctor).
     */
    protected final JiniFederation<?> fed;

    /**
     * The federation (from the ctor).
     */
    public JiniFederation<?> getFederation() {

        return fed;

    }

    /**
     * The {@link JobState} which is either set from the {@link Configuration}
     * (new job) or read from zookeeper (existing job) and thereafter
     * unchanging.
     */
    public S getJobState() {

        return jobState;

    }

    private S jobState;

    /**
     * Runs the master. SIGTERM (normal kill or ^C) will cancel the job,
     * including any running clients.
     * 
     * @return The {@link Future} for the master. Use {@link Future#get()} to
     *         await the outcome of the master.
     * 
     * @throws InterruptedException
     * @throws ExecutionException
     */
    final protected Future<Void> innerMain() {

        final Future<Void> future = fed.getExecutorService().submit(this);

        /*
         * Install a shutdown hook so that the master will cancel any running
         * clients if it is interrupted (normal kill will trigger this hook, but
         * it is also triggered for a normal exit from main()).
         */
        Runtime.getRuntime().addShutdownHook(new Thread() {

            public void run() {

                future.cancel(true/* mayInterruptIfRunning */);

                System.err.println("Shutting down: " + new Date());

            }

        });

        return future;

    }

    /**
     * 
     * @param fed
     * 
     * @throws ConfigurationException
     */
    protected TaskMaster(final JiniFederation<?> fed)
            throws ConfigurationException {

        if (fed == null)
            throw new IllegalArgumentException();

        this.fed = fed;

        /*
         * Use the name of the concrete instance of this class by default but
         * permit override of the component name on the command line.
         */
        final String component = System.getProperty("bigdata.component",
                getClass().getName());

        // The jini configuration specified on the command line.
        final Configuration config = fed.getClient().getConfiguration();

        // Initialize the job state.
        jobState = newJobState(component, config);

    }

    /**
     * Execute the master. If the master is interrupted, including by the signal
     * handler installed by {@link #innerMain()}, then the client tasks will be
     * cancelled. A simple <code>main()</code> can be written as follows:
     * 
     * <pre>
     * public static void main(final String[] args) {
     * 
     *     final JiniFederation fed = new JiniClient(args).connect();
     * 
     *     try {
     * 
     *         final TaskMaster task = new MyMaster(fed);
     * 
     *         task.execute();
     *         
     *     } finally {
     *     
     *         fed.shutdown();
     *         
     *     }
     * 
     * }
     * </pre>
     * 
     * Where <code>MyMaster</code> is a concrete subclass of
     * {@link TaskMaster}.
     * 
     * @throws InterruptedException
     * @throws ExecutionException
     */
    public void execute() throws InterruptedException, ExecutionException {

        // execute master wait for it to finish.
        try {

            innerMain().get();

        } finally {

            // always write the date when the master terminates.
            System.err.println("Done: " + new Date());

        }

    }

    /**
     * Wait a bit to discover some minimum #of data services. Then allocate the
     * clients to the data services. There can be more than one per data
     * service.
     * 
     * @return <code>null</code>
     * 
     * @see #execute()
     * 
     * @todo In my experience zookeeper (at least 3.0.1 and 3.1.0) has a
     *       tendency to drop sessions for the java client when under even
     *       moderate swapping. Because of this I am not verifying that the
     *       {@link TaskMaster} retains the {@link ZLock} for the job throughout
     *       its run. Doing so at this point is just begging for an aborted run.
     */
    final public Void call() throws Exception {

        /*
         * Setup the jobState.
         * 
         * Note: [jobState] will be replaced as a side-effect if there is an
         * existing instance of the job in zookeeper (same component and
         * jobName).
         */
        final ZLock zlock = setupJob();

        try {

            // note: take timestamp after discovering services!
            jobState.beginMillis = System.currentTimeMillis();

            // callback for overrides.
            beginJob(getJobState());

            try {
                
                // run the clients and wait for them to complete.
                runJob();

            } catch (CancellationException ex) {

                error(jobState, ex);
                
                throw ex;

            } catch (InterruptedException ex) {

                error(jobState, ex);
                
                throw ex;

            } catch (ExecutionException ex) {

                error(jobState, ex);
                
                throw ex;
        
            }
        
            success(jobState);

        } finally {

            tearDownJob(jobState, zlock);

        }

        if (jobState.forceOverflow) {

            forceOverflow();

        }

        return null;

    }

    /**
     * Start the client tasks and await their futures.
     * 
     * @throws Exception
     *             Client execution problem.
     * @throws InterruptedException
     *             Master interrupted awaiting clients.
     */
    protected void runJob() throws Exception, InterruptedException {

        final long begin = System.currentTimeMillis();

        // unless successful.
        boolean failure = true;

        try {

            startClients();

            awaitAll();

            failure = false;

        } finally {

            final long elapsed = System.currentTimeMillis() - begin;

            if (log.isInfoEnabled())
                log.info("Done: " + (failure ? "failure" : "success")
                        + ", elapsed=" + elapsed);

        }

    }

    /**
     * Distributes the clients to the services on which they will execute and
     * returns a map containing their {@link Future}s. The kind of service on
     * which the clients are run is determined by
     * {@link JobState#clientsTemplate} but must implement
     * {@link IRemoteExecutor}. Clients are assigned to the services using a
     * stable ordered assignment {@link JobState#clientServiceUUIDs}. If there
     * are more clients than services, then some services will be tasked with
     * more than one client. If there is a problem submitting the clients then
     * any clients already submitted will be canceled and the original exception
     * will be thrown out of this method.
     * 
     * @throws IOException
     *             If there is an RMI problem submitting the clients to the
     *             {@link IRemoteExecutor}s.
     * @throws ConfigurationException
     * 
     * @see {@link JobState#futures}
     */
    protected void startClients() throws IOException {

        if (log.isInfoEnabled())
            log.info("Will run " + jobState.nclients);

        jobState.futures = new LinkedHashMap<Integer, Future<?/* U */>>(
                jobState.nclients/* initialCapacity */);

        // #of clients that were started successfully.
        int nstarted = 0;
        try {

            for (int clientNum = 0; clientNum < jobState.nclients; clientNum++) {

                final ServiceItem serviceItem = jobState.clientServiceMap
                        .getServiceItem(clientNum);

                if (serviceItem == null) {

                    /*
                     * Note: The ServiceItem should have been resolved when we
                     * setup the JobState, even if the JobState was read from
                     * zookeeper.
                     */
                    throw new RuntimeException(
                            "ServiceItem not resolved? client#=" + clientNum);

                }

                if (!(serviceItem.service instanceof IRemoteExecutor)) {

                    throw new RuntimeException("Service does not implement "
                            + IRemoteExecutor.class + ", serviceItem="
                            + serviceItem);

                }

                final IRemoteExecutor service = (IRemoteExecutor) serviceItem.service;

                final Callable<U> clientTask = newClientTask(clientNum);

                if (log.isInfoEnabled())
                    log.info("Running client#=" + clientNum + " on "
                            + serviceItem);

                jobState.futures.put(clientNum, (Future<U>) service
                        .submit(clientTask));

                nstarted++;

            } // start the next client.

        } finally {

            if (nstarted < jobState.nclients) {

                log.error("Aborting : could not start client(s): nstarted="
                        + nstarted + ", nclients=" + jobState.nclients);

                cancelAll(true/* mayInterruptIfRunning */);

            }

        }

    }

    /**
     * Await the completion of the {@link Future}. If any client fails then the
     * remaining clients will be canceled.
     * 
     * @throws IllegalStateException
     *             if {@link JobState#futures} is <code>null</code>.
     * @throws ExecutionException
     *             for the first client whose failure is noticed.
     * @throws InterruptedException
     *             if the master is interrupted while awaiting the
     *             {@link Future}s.
     */
    protected void awaitAll() throws ExecutionException, InterruptedException {

        try {

            while (!allDone()) {

                final int nremaining = jobState.futures.size();

                if (log.isDebugEnabled())
                    log.debug("#remaining futures=" + nremaining);

                if (nremaining < 10)
                    // sleep a bit before rechecking the futures.
                    Thread.sleep(1000/* ms */);
                else
                    // sleep longer if there are more clients.
                    Thread.sleep(10000/* ms */);

            }

        } catch (InterruptedException t) {

            /*
             * Cancel all futures on error.
             */

            log.error("Cancelling job: cause=" + t);

            try {

                cancelAll(true/* mayInterruptIfRunning */);

            } catch (Throwable t2) {

                log.error(t2);

            }

            throw new RuntimeException(t);

        } catch (ExecutionException t) {

            /*
             * Cancel all futures on error.
             */

            log.error("Cancelling job: cause=" + t);

            try {

                cancelAll(true/* mayInterruptIfRunning */);

            } catch (Throwable t2) {

                log.error(t2);

            }

            throw new RuntimeException(t);

        }

    }

    /**
     * Callback invoked when the job is done executing (normal completion) but
     * is still holding the {@link ZLock} for the {@link JobState}. The default
     * implementation destroys the znodes for the job since it is done
     * executing.
     * 
     * @throws Exception
     */
    protected void success(final S jobState) throws Exception {

        // timestamp after the job is done.
        jobState.endMillis = System.currentTimeMillis();

        if (log.isInfoEnabled())
            log.info("Clients done: elapsed=" + jobState.getElapsedMillis());

        /*
         * This is the commit point corresponding to the end of the job.
         */
        System.out.println("commit point: "
                + getFederation().getLastCommitTime());

        /*
         * Delete zookeeper state when the job completes successfully.
         */
        ZooHelper.destroyZNodes(fed.getZookeeperAccessor().getZookeeper(),
                jobState.getJobZPath(fed), 0/* depth */);

    }

    /**
     * Callback invoked if an exception is thrown during the job execution. The
     * {@link JobState#endMillis} is set by this method, the client tasks are
     * canceled, and an error message is logged. By default, the znode for the
     * job is not destroyed. You can destroy a terminated job using zookeeper or
     * automatically destroy a pre-existing job when re-submitting the job using
     * {@link ConfigurationOptions#DELETE_JOB}.
     * <p>
     * Note: This method should not throw anything since that could cause the
     * root cause of the job error to be masked.
     * 
     * @param jobState
     *            The {@link JobState}.
     * @param t
     *            The exception.
     */
    protected void error(final S jobState, final Throwable t) {

        /*
         * Defensive implementation designed to be safe(r) if there is a log
         * configuration issue, etc.
         */
        
        try {

            // timestamp after the job is done.
            jobState.endMillis = System.currentTimeMillis();

            log.error("Abort: elapsed=" + jobState.getElapsedMillis()
                    + " : cause=" + t, t);
            
        } finally {

            try {

                // cancel any running clients.
                cancelAll(true/* mayInterruptIfRunning */);

            } catch (Throwable t2) {

                t2.printStackTrace(System.err);

            }

        }

    }
    
    /**
     * Callback invoked when the job is done executing (any completion) but has
     * not yet release the {@link ZLock} for the {@link JobState}. The default
     * releases the {@link ZLock}. It may be extended to handle other cleanup.
     * 
     * @throws Exception
     */
    protected void tearDownJob(final S jobState, final ZLock zlock)
            throws Exception {

        zlock.unlock();

    }

    /**
     * Return a {@link JobState}.
     * 
     * @param component
     *            The component.
     * @param config
     *            The configuration.
     * 
     * @return The {@link JobState}.
     */
    abstract protected S newJobState(String component, Configuration config)
            throws ConfigurationException;

    /**
     * Return a client to be executed on a remote data service. The client can
     * obtain access to the {@link IBigdataFederation} when it executes on the
     * remote data service if it implements {@link IDataServiceCallable}. You
     * can use {@link AbstractClientTask} as a starting point.
     * 
     * @param clientNum
     *            The client number.
     * 
     * @return The client task.
     * 
     * @see AbstractClientTask
     */
    abstract protected T newClientTask(final int clientNum);

    /**
     * Callback invoked when the job is ready to execute and is holding the
     * {@link ZLock} for the {@link JobState}. This may be extended to register
     * indices, etc. The default implementation handles the setup of the
     * optional index partition metadata dumps.
     * 
     * @throws Exception
     * 
     * @see ConfigurationOptions#INDEX_DUMP_DIR
     * @see ConfigurationOptions#INDEX_DUMP_NAMESPACE
     */
    protected void beginJob(final S jobState) throws Exception {

        if (jobState.indexDumpDir != null) {

            // runs @t0, 1m, 2m, ... 9m.
            fed.addScheduledTask(new ScheduledDumpTask(fed,
                    jobState.indexDumpNamespace, 10/* nruns */,
                    jobState.indexDumpDir, "indexDump", TimeUnit.MINUTES),
                    0/* initialDelay */, 1/* delay */, TimeUnit.MINUTES);

            // runs @t10m, 20m, 30m, ... 50m.
            fed.addScheduledTask(new ScheduledDumpTask(fed,
                    jobState.indexDumpNamespace, 5/* nruns */,
                    jobState.indexDumpDir, "indexDump", TimeUnit.MINUTES),
                    10/* initialDelay */, 10/* delay */, TimeUnit.MINUTES);

            // runs @t1h, 2h, ... until cancelled.
            fed.addScheduledTask(new ScheduledDumpTask(fed,
                    jobState.indexDumpNamespace, Integer.MAX_VALUE/* nruns */,
                    jobState.indexDumpDir, "indexDump", TimeUnit.MINUTES),
                    1/* initialDelay */, 1/* delay */, TimeUnit.HOURS);

        }

    }

    /**
     * Sets up the {@link JobState} in zookeeper, including the assignment of
     * service {@link UUID}s to each client. {@link #jobState} will be replaced
     * with the {@link JobState} read from zookeeper if a pre-existing job is
     * found in zookeeper.
     * 
     * @return The global lock for the master running the job.
     * 
     * @throws KeeperException
     * @throws InterruptedException
     * @throws TimeoutException
     */
    protected ZLock setupJob() throws KeeperException, InterruptedException,
            TimeoutException {

        final ZooKeeper zookeeper = fed.getZookeeperAccessor().getZookeeper();

        try {
            // ensure znode exists.
            zookeeper.create(fed.getZooConfig().zroot + "/"
                    + BigdataZooDefs.JOBS, new byte[0], fed.getZooConfig().acl,
                    CreateMode.PERSISTENT);
        } catch (NodeExistsException ex) {
            // ignore.
        }

        final String jobClassZPath = jobState.getJobClassZPath(fed);

        try {
            // ensure znode exists.
            zookeeper.create(jobClassZPath, new byte[0],
                    fed.getZooConfig().acl, CreateMode.PERSISTENT);
        } catch (NodeExistsException ex) {
            // ignore.
        }

        /*
         * Use a global lock to protect the job.
         * 
         * Note: We just created parent of this lock node (or at any rate,
         * ensured that it exists).
         */
        final ZLock zlock = ZLockImpl.getLock(zookeeper, jobClassZPath + "/"
                + "locknode_" + jobState.jobName, fed.getZooConfig().acl);

        zlock.lock();
        try {

            final String jobZPath = jobState.getJobZPath(fed);

            if (jobState.deleteJob
                    && zookeeper.exists(jobZPath, false/* watch */) != null) {

                /*
                 * Delete old job.
                 */

                log.warn("Deleting old job: " + jobZPath);

                ZooHelper.destroyZNodes(fed.getZookeeperAccessor()
                        .getZookeeper(), jobZPath, 0/* depth */);

                // detach the performance counters for the old job.
                detachPerformanceCounters();
                
            }

            try {

                // create znode that is the root for the job.
                zookeeper.create(jobZPath, SerializerUtil.serialize(jobState),
                        fed.getZooConfig().acl, CreateMode.PERSISTENT);

                if (log.isInfoEnabled())
                    log.info("New job: " + jobState);

                try {

                    /*
                     * Assign clients to services.
                     */
                    final DiscoveredServices discoveredServices = new DiscoverServicesWithPreconditionsTask()
                            .call();

                    jobState.clientServiceMap
                            .assignClientsToServices(discoveredServices.clientServiceItems);

                    jobState.aggregatorServiceMap
                            .assignClientsToServices(discoveredServices.aggregatorServiceItems);

                    // write those assignments into zookeeper.
                    zookeeper.setData(jobZPath, SerializerUtil
                            .serialize(jobState), -1/* version */);

                    if (log.isInfoEnabled())
                        log.info("Wrote client assignments into zookeeper.");

                } catch (Throwable t) {

                    /*
                     * Since we created the jobState znode, delete the jobState
                     * while we are still holding the zlock.
                     */
                    try {
                        zookeeper.delete(jobZPath, -1/* version */);
                    } catch (Throwable t2) {
                        log.error(t2);
                    }

                    throw new RuntimeException(t);

                }

            } catch (NodeExistsException ex) {

                /*
                 * Resuming a job already in progress and/or providing backup
                 * clients for a job that is currently running.
                 * 
                 * Note: We use the client to data service UUID assignments read
                 * from the znode data which are part of the jobState
                 * 
                 * @todo stable assignments are only required when clients will
                 * read or write local data. This is not the common case and
                 * should be a declarative configuration option.
                 */

                jobState = (S) SerializerUtil.deserialize(zookeeper.getData(
                        jobZPath, false, new Stat()));

                jobState.clientServiceMap.resolveServiceUUIDs(fed);

                jobState.aggregatorServiceMap.resolveServiceUUIDs(fed);

                jobState.resumedJob = true;

                log.warn("Pre-existing job: " + jobZPath);

            }

        } catch (KeeperException t) {

            zlock.unlock();

            throw t;

        } catch (InterruptedException t) {

            zlock.unlock();

            throw t;

        } catch (Throwable t) {

            zlock.unlock();

            throw new RuntimeException(t);

        }

        return zlock;

    }

    /**
     * Detach the performance counters for the job.
     * 
     * @todo does not remove the counters on the LBS, just in local memory so
     *       this is not much help. It would only be useful if we re-ran the
     *       same job within the same JVM instance.
     */
    protected void detachPerformanceCounters() {

        getFederation().getServiceCounterSet().makePath("Jobs").detach(
                jobState.jobName);

    }
    
    /**
     * Attach to the counters reported by the client to the LBS.
     */
    protected void attachPerformanceCounters(final CounterSet counterSet) {

        if(counterSet == null) {
            
            throw new IllegalArgumentException();
            
        }
        
        getFederation().getServiceCounterSet().makePath("Jobs").makePath(
                getJobState().jobName).attach(counterSet, true/* replace */);
        

    }
    
    /**
     * Class used to return the discovered services.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     * @version $Id$
     */
    public static class DiscoveredServices {

        /**
         * The services on which the clients will be executed.
         */
        final public ServiceItem[] clientServiceItems;

        /**
         * The services on which the aggregators will be executed (and an empty
         * array if no aggregator services were requested).
         */
        final public ServiceItem[] aggregatorServiceItems;

        public DiscoveredServices(final ServiceItem[] clientServiceItems,
                final ServiceItem[] aggregatorServiceItems) {

            if (clientServiceItems == null)
                throw new IllegalArgumentException();

            if (aggregatorServiceItems == null)
                throw new IllegalArgumentException();

            this.clientServiceItems = clientServiceItems;

            this.aggregatorServiceItems = aggregatorServiceItems;

        }

    }

    /**
     * Class awaits discovery of all services required by the {@link JobState}
     * up to the {@link JobState#servicesDiscoveryTimeout} and returns the
     * {@link ServiceItem}s for the services on which the clients should be
     * executed.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     * @version $Id$
     */
    private class DiscoverServicesWithPreconditionsTask implements
            Callable<DiscoveredServices> {

        public DiscoverServicesWithPreconditionsTask() {

        }

        /**
         * Await discovery of the services described by
         * {@link JobState#servicesTemplates} and by
         * {@link JobState#clientsTemplate}.
         * 
         * @return An object reporting the discovered services which match the
         *         {@link JobState#clientsTemplate} and the optional
         *         {@link JobState#aggregatorsTemplate}.
         */
        public DiscoveredServices call() throws Exception {

            if (jobState == null)
                throw new IllegalArgumentException();

            if (jobState.servicesTemplates == null)
                throw new IllegalArgumentException();

            if (jobState.servicesDiscoveryTimeout <= 0)
                throw new IllegalArgumentException();

            /*
             * This is the task that will give us the services on which the
             * clients will execute.
             */
            final Future<ServiceItem[]> discoverClientServicesFuture = fed
                    .getExecutorService().submit(
                            new DiscoverServices(fed, jobState.clientsTemplate,
                                    jobState.servicesDiscoveryTimeout));

            final Future<ServiceItem[]> discoverAggregatorServicesFuture;
            if (jobState.aggregatorsTemplate != null) {
                /*
                 * This task will give us the services on which the
                 * aggregator(s) will execute.
                 */
                discoverAggregatorServicesFuture = fed.getExecutorService()
                        .submit(
                                new DiscoverServices(fed,
                                        jobState.aggregatorsTemplate,
                                        jobState.servicesDiscoveryTimeout));
            } else {
                // aggregator is not used.
                discoverAggregatorServicesFuture = null;
            }

            /*
             * Additional tasks for the other services which must be discovered
             * as pre-conditions before the job can execute.
             */
            final List<Callable<ServiceItem[]>> tasks = new LinkedList<Callable<ServiceItem[]>>();

            for (ServicesTemplate t : jobState.servicesTemplates) {

                tasks.add(new DiscoverServices(fed, t,
                        jobState.servicesDiscoveryTimeout));

            }

            // submit all tasks in parallel.
            final Future<ServiceItem[]>[] futures = fed.getExecutorService()
                    .invokeAll(tasks).toArray(new Future[tasks.size()]);

            // Assemble a list of errors.
            final List<Throwable> causes = new LinkedList<Throwable>();

            /*
             * Get the future, which gives the services on which we will execute
             * the clients.
             */
            final ServiceItem[] clientServiceItems = discoverClientServicesFuture
                    .get();

            if (clientServiceItems.length < jobState.clientsTemplate.minMatches) {

                final String msg = "Not enough services to run clients: found="
                        + clientServiceItems.length + ", required="
                        + jobState.clientsTemplate.minMatches + ", template="
                        + jobState.clientsTemplate;

                log.error(msg);

                causes.add(new RuntimeException(msg));

            }

            final ServiceItem[] aggregatorServiceItems;
            if (jobState.aggregatorsTemplate != null) {
                /*
                 * Get the future, which gives the services on which we will
                 * execute the aggregators.
                 */
                aggregatorServiceItems = discoverAggregatorServicesFuture.get();
                if (aggregatorServiceItems.length < jobState.aggregatorsTemplate.minMatches) {

                    final String msg = "Not enough services to run aggregators: found="
                            + aggregatorServiceItems.length
                            + ", required="
                            + jobState.aggregatorsTemplate.minMatches
                            + ", template=" + jobState.aggregatorsTemplate;

                    log.error(msg);

                    causes.add(new RuntimeException(msg));

                }
            } else {
                // No aggregators (empty array).
                aggregatorServiceItems = new ServiceItem[0];
            }

            /*
             * Check the other pre-conditions for discovered services.
             */

            for (int i = 0; i < futures.length; i++) {

                final Future<ServiceItem[]> f = futures[i];

                final ServicesTemplate servicesTemplate = jobState.servicesTemplates[i];

                try {

                    final ServiceItem[] a = f.get();

                    if (a.length < servicesTemplate.minMatches) {

                        final String msg = "Not enough services: found="
                                + a.length + ", required="
                                + servicesTemplate.minMatches + ", template="
                                + servicesTemplate;

                        // log error w/ specific cause of rejected run.
                        log.error(msg);

                        // add msg to list of causes.
                        causes.add(new RuntimeException(msg));

                    }

                } catch (Throwable ex) {

                    // add thrown exception to list of causes.
                    causes.add(ex);

                }

            }

            if (!causes.isEmpty()) {

                throw new ExecutionExceptions(causes);

            }

            return new DiscoveredServices(clientServiceItems,
                    aggregatorServiceItems);

        }

    }

    /**
     * Check the futures.
     * 
     * @return <code>true</code> when no more tasks are running.
     * 
     * @throws ExecutionException
     * @throws InterruptedException
     */
    protected boolean allDone() throws InterruptedException, ExecutionException {

        if (jobState.futures == null)
            throw new IllegalStateException();

        // Note: used to avoid concurrent modification of [futures].
        final List<Integer> finished = new LinkedList<Integer>();

        int nremaining = jobState.futures.size();

        for (Map.Entry<Integer, Future<?/* U */>> entry : jobState.futures
                .entrySet()) {

            final int clientNum = entry.getKey();

            final Future<?/* U */> future = entry.getValue();

            if (future.isDone()) {

                /*
                 * Note: test the client's future and halt if the client fails.
                 */
                final Object value = future.get();

                nremaining--;

                System.out.println("Done: " + new Date() + " : clientNum="
                        + clientNum + " of " + jobState.nclients + " with "
                        + nremaining + " remaining : result=" + value);

                try {
                    notifyOutcome(clientNum, (U) value);
                } catch (Throwable t) {
                    log.error("Ignoring thrown exception: " + t);
                }

                finished.add(clientNum);

            }

        }

        for (int clientNum : finished) {

            jobState.futures.remove(clientNum);

        }

        // finished iff no more futures.
        return jobState.futures.isEmpty();

    }

    /**
     * Cancel the futures.
     * 
     * @param futures
     *            The futures.
     * @param mayInterruptIfRunning
     *            If the tasks for the futures may be interrupted.
     */
    synchronized// since is invoked from execute() and runClients()
    protected void cancelAll(final boolean mayInterruptIfRunning) {

        if (jobState.futures == null) {

            /*
             * Note: This is ignored since it is possible that cancelAll() is
             * invoked from execute() before the client tasks have been assigned
             * their futures.
             */

            return;

        }

        log.warn("Cancelling all futures: nfutures=" + jobState.futures.size());

        final Iterator<Future<?/* U */>> itr = jobState.futures.values()
                .iterator();

        while (itr.hasNext()) {

            final Future<?/* U */> f = itr.next();

            if (!f.isDone()) {

                f.cancel(mayInterruptIfRunning);

            }

            itr.remove();

        }

    }

    /**
     * Force overflow on all discovered {@link IDataService}.
     * 
     * @see ConfigurationOptions#FORCE_OVERFLOW
     * 
     * @todo This is an operation that we would like to run once by the master
     *       which actually executes the clients even if there are multiple
     *       masters (multiple master support is not really all there yet and
     *       there are interactions with how the client tasks handle multiple
     *       instances of themselves so this is all forward looking).
     */
    protected void forceOverflow() {

        System.out.println("Forcing overflow: now=" + new Date());

		fed.forceOverflow(true/* compactingMerge */, true/* truncateJournal */);

        System.out.println("Forced overflow: now=" + new Date());

    }

    /**
     * Callback for the master to consume the outcome of the client's
     * {@link Future} (default is NOP).
     * 
     * @param clientNum
     *            The client number.
     * @param value
     *            The value returned by the {@link Future}.
     */
    protected void notifyOutcome(final int clientNum, final U value) {

    }

}
