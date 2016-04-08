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
 * Created on Jan 15, 2009
 */

package com.bigdata.jini.start.config;


import org.apache.log4j.Logger;

import com.bigdata.jini.lookup.entry.Hostname;
import com.bigdata.jini.start.MonitorCreatePhysicalServiceLocksTask;
import com.bigdata.service.DataService;
import com.bigdata.service.IDataService;
import com.bigdata.service.MetadataService;
import com.bigdata.service.jini.DataServer.AdministrableDataService;
import com.bigdata.service.jini.MetadataServer.AdministrableMetadataService;

/**
 * Places a limit on the #of instances of a service which can be created on the
 * same host. Jini is used to discover the instances of the specified class of
 * service. The constraint is satisified iff the #of discovered instances is
 * less than the maximum.
 * <p>
 * Note: Since jini does not directly report the hostname of a service, this
 * relies on the {@link Hostname} attribute which is placed onto the bigdata
 * service instances.
 * <p>
 * Note: Since a {@link MetadataService} is a {@link DataService}, you want to
 * use {@link AdministrableMetadataService} and {@link AdministrableDataService}
 * to specify the constraints for those services. If you use only
 * {@link IDataService} or {@link DataService} the constraint will be applied to
 * both and instances of both will be counted against the maximum.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 * @see MonitorCreatePhysicalServiceLocksTask
 * 
 * @todo Other kinds of constraints could limit the mixture of services that may
 *       run on a host. For example, do not run any other service on a host
 *       running zookeeper (or listed as a zookeeper server) or on a host
 *       running the {@link MetadataService}.
 */
abstract public class MaxServicesPerHostConstraint implements IServiceConstraint {

    protected static final Logger log = Logger.getLogger(MaxServicesPerHostConstraint.class);
    
//    protected static final boolean INFO = log.isInfoEnabled();
    
//    protected final String className;
    
    protected final int maxServices;
    
//    /**
//     * The timeout in milliseconds for service discovery.
//     */
//    protected final long timeout;

    public String toString() {
//        className=" + className + //
//            ", 
        return getClass().getName() + //
                "{ maxServices=" + maxServices + //
//                ", timeout=" + timeout + //
                "}";
        
    }
    
    /**
     * 
     * @param maxServices
     *            The maximum #of services which are instances of that class or
     *            interface which may run on any given host.
     */
    public MaxServicesPerHostConstraint(final int maxServices) {

        if (maxServices <= 0)
            throw new IllegalArgumentException();
        
//        this.className = className;
        
        this.maxServices = maxServices;
        
//        this.timeout = 2000;// ms.
        
    }

}
