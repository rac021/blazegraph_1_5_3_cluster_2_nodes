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
 * Created on Mar 22, 2007
 */

package com.bigdata.service.jini;

import java.net.InetAddress;
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

import com.bigdata.service.ClientService;
import com.bigdata.service.DataService;
import com.bigdata.service.ClientService.ClientServiceFederationDelegate;
import com.sun.jini.start.LifeCycle;
import com.sun.jini.start.ServiceDescriptor;
import com.sun.jini.start.ServiceStarter;

/**
 * The bigdata client server (a container for distributed application tasks).
 * <p>
 * The {@link ClientServer} starts the {@link ClientService}. The server and
 * service are configured using a {@link Configuration} file whose name is
 * passed to the {@link ClientServer#ClientServer(String[])} constructor or
 * {@link #main(String[])}.
 * <p>
 * 
 * @see src/resources/config for sample configurations.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class ClientServer extends AbstractServer {

    /**
     * Options for this server.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     * @version $Id$
     */
    public interface Options extends AdministrableClientService.Options {
        
    }
    
    /**
     * Creates a new {@link ClientServer}.
     * 
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
    public ClientServer(final String[] args, final LifeCycle lifeCycle) {

        super(args, lifeCycle);

    }

    /**
     * Starts a new {@link ClientServer}.  This can be done programmatically
     * by executing
     * <pre>
     *    new DataServer(args, new FakeLifeCycle()).run();
     * </pre>
     * within a {@link Thread}.
     * 
     * @param args
     *            The name of the {@link Configuration} file for the service.
     */
    public static void main(final String[] args) {
        
        new ClientServer(args, new FakeLifeCycle()).run();
        
        System.exit(0);
//        Runtime.getRuntime().halt(0);
        
    }
    
    protected ClientService newService(final Properties properties) {

        final ClientService service = new AdministrableClientService(this,
                properties);
        
        /*
         * Setup a delegate that let's us customize some of the federation
         * behaviors on the behalf of the data service.
         * 
         * Note: We can't do this with the local or embedded federations since
         * they have only one client per federation and an attempt to set the
         * delegate more than once will cause an exception to be thrown!
         */
        getClient().setDelegate(new ClientServiceFederationDelegate(service));
        
        return service;

    }

//    /**
//     * Extends the behavior to close and delete the journal in use by the data
//     * service.
//     */
//    public void destroy() {
//
//        super.destroy();
//
//    }

    /**
     * Adds jini administration interfaces to the basic {@link DataService}.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     * @version $Id$
     */
    public static class AdministrableClientService extends ClientService implements
            RemoteAdministrable, RemoteDestroyAdmin {
        
        final protected ClientServer server;

        public AdministrableClientService(final ClientServer server,
                final Properties properties) {

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
         * 
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

                MDC.put("clientname", clientAddr.getHostName());

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

        @Override
        public JiniFederation<?> getFederation() {

            return server.getClient().getFederation();

        }

        /**
         * Extends the base behavior to return an RMI compatible proxy for the
         * {@link Future}.
         */
        @Override
        public Future<? extends Object> submit(
                final Callable<? extends Object> task) {

            return getFederation().getProxy(super.submit(task));

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
