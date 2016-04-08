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
 * Created on Jan 5, 2009
 */

package com.bigdata.jini.start.config;

import net.jini.config.Configuration;
import net.jini.config.ConfigurationException;
import net.jini.core.entry.Entry;

import com.bigdata.jini.start.IServiceListener;
import com.bigdata.jini.start.process.JiniServiceProcessHelper;
import com.bigdata.service.jini.ClientServer;
import com.bigdata.service.jini.JiniFederation;

/**
 * Configuration for the {@link ClientServer}.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class ClientServerConfiguration extends
        BigdataServiceConfiguration {

    /**
     * 
     */
    private static final long serialVersionUID = 2149864496289812203L;

    /**
     * @param config
     */
    public ClientServerConfiguration(Configuration config)
            throws ConfigurationException {

        super(ClientServer.class, config);

    }

    public ClientServiceStarter newServiceStarter(JiniFederation fed,
            IServiceListener listener, String zpath, Entry[] attributes)
            throws Exception {

        return new ClientServiceStarter(fed, listener, zpath, attributes);

    }

    public class ClientServiceStarter<V extends JiniServiceProcessHelper>
            extends BigdataServiceStarter<V> {

        /**
         * @param fed
         * @param listener
         * @param zpath
         */
        protected ClientServiceStarter(JiniFederation fed,
                IServiceListener listener, String zpath, Entry[] attributes) {

            super(fed, listener, zpath, attributes);

        }

//        @Override
//        protected NV getDataDir() {
//            
//            return new NV(LoadBalancerServer.Options.LOG_DIR, serviceDir
//                    .toString());
//            
//        }
        
    }

}
