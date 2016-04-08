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
 * Created on Mar 24, 2007
 */

package com.bigdata.service.jini;

import java.net.InetAddress;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.server.ServerNotActiveException;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

import net.jini.config.Configuration;
import net.jini.export.ServerContext;
import net.jini.io.context.ClientHost;
import net.jini.io.context.ClientSubject;
import net.jini.lookup.entry.Name;

import org.apache.log4j.MDC;

import com.bigdata.bop.engine.IQueryPeer;
import com.bigdata.btree.proc.IIndexProcedure;
import com.bigdata.service.IDataService;
import com.bigdata.service.MetadataService;
import com.bigdata.service.DataService.DataServiceFederationDelegate;
import com.sun.jini.start.LifeCycle;
import com.sun.jini.start.ServiceDescriptor;
import com.sun.jini.start.ServiceStarter;

/**
 * A metadata server.
 * <p>
 * The metadata server is used to manage the life cycles of scale-out indices
 * and exposes proxies for read and write operations on indices to clients.
 * Clients use index proxies, which automatically direct reads and writes to the
 * {@link IDataService} on which specific index partitions are located.
 * <p>
 * On startup, the metadata service discovers active data services configured in
 * the same group. While running, it tracks when data services start and stop so
 * that it can (re-)allocate index partitions as necessary.
 * 
 * @todo should destroy destroy the service instance or the persistent state as
 *       well? Locally, or as replicated?
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class MetadataServer extends DataServer {

    /**
     * Options for this server.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     * @version $Id$
     */
    public interface Options extends AdministrableMetadataService.Options {

    }
    
    /**
     * @param args
     *            Either the command line arguments or the arguments from the
     *            {@link ServiceDescriptor}. Either way they identify the jini
     *            {@link Configuration} (you may specify either a file or URL)
     *            and optional overrides for that {@link Configuration}.
     * @param lifeCycle
     *            The life cycle object. This is used if the server is started
     *            by the jini {@link ServiceStarter}. Otherwise specify a
     *            {@link FakeLifeCycle}.
     */
    public MetadataServer(final String[] args, final LifeCycle lifeCycle) {

        super(args, lifeCycle);
             
    }

    /**
     * Starts a new {@link MetadataServer}.  This can be done programmatically
     * by executing
     * <pre>
     *    new MetadataServer(args,new FakeLifeCycle()).run();
     * </pre>
     * within a {@link Thread}.
     * 
     * @param args
     *            The name of the {@link Configuration} file for the service.
     */
    public static void main(final String[] args) {

        new MetadataServer(args, new FakeLifeCycle()).run();

        System.exit(0);
//      Runtime.getRuntime().halt(0);

    }

    protected MetadataService newService(Properties properties) {

        properties = new Properties(properties);

        /*
         * Note: the MDS does not support overflow at this time so this
         * option is explicitly disabled. 
         */
        properties.setProperty(MetadataService.Options.OVERFLOW_ENABLED, "false");
        
        final MetadataService service = new AdministrableMetadataService(
                this, properties);
        
        /*
         * Setup a delegate that let's us customize some of the federation
         * behaviors on the behalf of the data service.
         */
        getClient().setDelegate(new DataServiceFederationDelegate(service));

        return service;
        
    }
    
    /**
     * Adds jini administration interfaces to the basic {@link MetadataService}.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     * @version $Id$
     */
    public static class AdministrableMetadataService extends MetadataService
            implements Remote, RemoteAdministrable, RemoteDestroyAdmin {
        
        protected final MetadataServer server;

        /**
         * @param properties
         */
        public AdministrableMetadataService(MetadataServer server,
                Properties properties) {

            super(properties);

            this.server = server;

        }

        public Object getAdmin() throws RemoteException {

            if (log.isInfoEnabled())
                log.info("" + getServiceUUID());

            return server.proxy;

        }

        /**
         * Adds the following parameters to the {@link MDC}
         * <dl>
         * 
         * <dt>clientname
         * <dt>
         * <dd>The hostname or IP address of the client making the request.</dd>
         * </dl>
         * 
         * Note: {@link InetAddress#getHostName()} is used. This method makes a
         * one-time best effort attempt to resolve the host name from the
         * {@link InetAddress}.
         * 
         * @todo we could pass the class {@link ClientSubject} to obtain the
         *       authenticated identity of the client (if any) for an incoming
         *       remote call.
         */
        @Override
        protected void setupLoggingContext() {
            
            super.setupLoggingContext();
            
            try {
                
                final InetAddress clientAddr = ((ClientHost) ServerContext
                        .getServerContextElement(ClientHost.class))
                        .getClientHost();
                
                MDC.put("clientname",clientAddr.getHostName());
                
            } catch (ServerNotActiveException e) {
                
                /*
                 * This exception gets thrown if the client has made a direct
                 * (vs RMI) call so we just ignore it.
                 */
                
            }
            
        }

        @Override
        protected void clearLoggingContext() {
            
            MDC.remove("clientname");

            super.clearLoggingContext();
            
        }

        @Override
        public JiniFederation<?> getFederation() {

            return server.getClient().getFederation();
            
        }

        /*
         * DestroyAdmin
         */

        @Override
        synchronized public void destroy() {

            if (!server.isShuttingDown()) {

                /*
                 * Run thread which will destroy the service (asynchronous).
                 * 
                 * Note: By running this is a thread, we avoid closing the
                 * service end point during the method call.
                 */

                server.runDestroy();

            } else if (isOpen()) {

                /*
                 * The server is already shutting down, so invoke our super
                 * class behavior to destroy the persistent state.
                 */

                super.destroy();

            }

        }

        @Override
        synchronized public void shutdown() {
            
            // normal service shutdown (blocks).
            super.shutdown();
            
            // jini service and server shutdown.
            server.shutdownNow(false/*destroy*/);
            
        }

        @Override
        synchronized public void shutdownNow() {
            
            // immediate service shutdown (blocks).
            super.shutdownNow();
            
            // jini service and server shutdown.
            server.shutdownNow(false/*destroy*/);
            
        }

//        public IMetadataService getMetadataService() {
//
//            return this;
//            
//        }
        
        /**
         * Extends the base behavior to return an RMI compatible proxy.
         */
        @Override
        public Future<? extends Object> submit(Callable<? extends Object> task) {

            return getFederation().getProxy(super.submit(task));
            
        }

        /**
         * Extends the base behavior to return an RMI compatible proxy for the
         * {@link IQueryEngine}.
         */
        @Override
        public IQueryPeer getQueryEngine() {
            
			synchronized (this) {
				if (queryPeerProxy == null) {
					/*
					 * Note: DGC is not necessary since the DataService has a
					 * hard reference to the QueryEngine.
					 */
					queryPeerProxy = getFederation().getProxy(
							super.getQueryEngine(), false/* enableDGC */);
				}
				return queryPeerProxy;
			}

        }
        private IQueryPeer queryPeerProxy = null;
        
        /**
         * Extends the base behavior to return an RMI compatible proxy for the
         * {@link Future}.
         */
        @Override
        public Future submit(final long tx, final String name,
                final IIndexProcedure proc) {

            return getFederation().getProxy(super.submit(tx, name, proc));

        }
        
        /**
         * Extends the base behavior to return a {@link Name} of the service
         * from the {@link Configuration}. If no name was specified in the
         * {@link Configuration} then the value returned by the base class is
         * returned instead.
         */
        @Override
        public String getServiceName() {

            String s = server.getServiceName();

            if (s == null)
                s = super.getServiceName();

            return s;

        }

    }

}
