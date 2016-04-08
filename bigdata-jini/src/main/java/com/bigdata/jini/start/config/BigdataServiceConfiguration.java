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
 * Created on Jan 4, 2009
 */

package com.bigdata.jini.start.config;

import java.util.Properties;

import net.jini.config.Configuration;
import net.jini.config.ConfigurationException;
import net.jini.core.entry.Entry;

import com.bigdata.jini.start.IServiceListener;
import com.bigdata.jini.start.process.JiniServiceProcessHelper;
import com.bigdata.service.jini.AbstractServer;
import com.bigdata.service.jini.DataServer;
import com.bigdata.service.jini.JiniFederation;
import com.bigdata.util.NV;

/**
 * A bigdata service. Most services have additional parameters which must be
 * specified. Those services are handled by subclasses.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class BigdataServiceConfiguration extends
        JiniServiceConfiguration {

    /**
     * 
     */
    private static final long serialVersionUID = 734513805833840009L;

    /**
     * Options for the bigdata services.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     * @version $Id$
     */
    public interface Options extends JiniServiceConfiguration.Options {
        
    }
    
    /**
     * @param cls
     * @param config
     * @throws ConfigurationException
     */
    public BigdataServiceConfiguration(Class<? extends AbstractServer> cls,
            Configuration config) throws ConfigurationException {

        super(cls.getName(), config);

        if (log4j == null) {
            
            throw new ConfigurationException("Must specify: " + Options.LOG4J);
            
        }
        
    }

    protected void toString(StringBuilder sb) {

        super.toString(sb);

    }

    public BigdataServiceStarter newServiceStarter(final JiniFederation fed,
            final IServiceListener listener, final String logicalServiceZPath,
            final Entry[] attributes) throws Exception {

        return new BigdataServiceStarter(fed, listener, logicalServiceZPath,
                attributes);

    }
    
    /**
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     * @version $Id$
     * @param <V>
     */
    public class BigdataServiceStarter<V extends JiniServiceProcessHelper> extends
            JiniServiceStarter<V> {

        /**
         * @param fed
         * @param listener
         * @param logicalServiceZPath
         */
        protected BigdataServiceStarter(JiniFederation fed,
                IServiceListener listener, String logicalServiceZPath,
                Entry[] attributes) {

            super(fed, listener, logicalServiceZPath, attributes);
            
        }

        /**
         * Returns the "dataDir" configuration property for the service -or-
         * <code>null</code> if the service does not use a data directory.
         * <p>
         * Note: Subclasses for {@link DataServer}, etc must add service
         * specific properties, such the dataDir, which can only be determined
         * at runtime.
         * 
         * @see JavaServiceStarter#serviceDir
         */
        protected NV getDataDir() {
        
            return null;
            
        }

        /**
         * Returns the service configuration properties (allows the override or
         * addition of those properties at service creation time).
         * <p>
         * Note: If {@link #getDataDir()} returns non-<code>null</code> then
         * that property-value binding will be included in the returned array
         * unless a binding already exists for that property (this avoids
         * overwrite of an explicitly configured property value).
         */
        @Override
        protected Properties getProperties(final Properties properties) {

            final NV dataDir = getDataDir();

            if (dataDir != null) {

                /*
                 * Note: [dataDir] represents a default. if the configuration
                 * gave an explicit NV for this property then we DO NOT use the
                 * [dataDir] since that would overwrite the configured value.
                 */ 
                if (properties.getProperty(dataDir.getName()) == null) {

                    // the data directory for this service type.
                    properties.setProperty(dataDir.getName(), dataDir
                            .getValue());

                }

            }

            return properties;

        }
        
    }

}
