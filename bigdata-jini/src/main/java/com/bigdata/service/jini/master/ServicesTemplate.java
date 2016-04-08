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
 * Created on Apr 24, 2009
 */

package com.bigdata.service.jini.master;

import java.io.Serializable;

import net.jini.core.lookup.ServiceTemplate;
import net.jini.lookup.ServiceItemFilter;

/**
 * A template for matching some number and type of services.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class ServicesTemplate implements Serializable {

    /**
     * 
     */
    private static final long serialVersionUID = -7756684114389681575L;

    /**
     * The minimum #of services to be matched.
     */
    final public int minMatches;

    /**
     * The template to be matched.
     */
    final public ServiceTemplate template;
    
    /**
     * An optional filter to be applied.
     */
    final public ServiceItemFilter filter;

    public String toString() {
        
        return getClass().getName() + //
                "{ minMatches=" + minMatches + //
                ", template=" + template + //
                ", filter=" + filter + //
                "}";
        
    }

    /**
     * 
     * @param minMatches
     *            The minimum #of services to be matched.
     * @param template
     *            The template to be matched.
     * @param filter
     *            An optional filter.
     *            
     * @throws IllegalArgumentException
     *             if <i>minMatches</i> is LT zero.
     * @throws IllegalArgumentException
     *             if <i>template</i> is <code>null</code>.
     */
    public ServicesTemplate(final int minMatches,
            final ServiceTemplate template, final ServiceItemFilter filter) {

        if (minMatches <= 0)
            throw new IllegalArgumentException();

        if (template == null)
            throw new IllegalArgumentException();

        this.minMatches = minMatches;

        this.template = template;
        
        this.filter = filter;

    }

}
