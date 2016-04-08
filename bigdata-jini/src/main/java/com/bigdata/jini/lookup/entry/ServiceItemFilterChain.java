package com.bigdata.jini.lookup.entry;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;

import net.jini.core.lookup.ServiceItem;
import net.jini.lookup.ServiceItemFilter;

/**
 * A chain of filters applied in the order in which they were added.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class ServiceItemFilterChain implements ServiceItemFilter, Serializable {

    /**
     * 
     */
    private static final long serialVersionUID = 8754783713903293241L;
    
    private final List<ServiceItemFilter> chain = new LinkedList<ServiceItemFilter>();

    public ServiceItemFilterChain() {

    }

    public ServiceItemFilterChain(ServiceItemFilter filter) {

        add( filter );
        
    }

    public ServiceItemFilterChain(final ServiceItemFilter[] filter) {

        if (filter == null)
            throw new IllegalArgumentException();

        for(ServiceItemFilter f : filter) {

            add( f );
            
        }
        
    }

    public void add(final ServiceItemFilter f) {

        if (f == null)
            throw new IllegalArgumentException();

        chain.add(f);

    }

    public boolean check(final ServiceItem serviceItem) {

        for (ServiceItemFilter f : chain) {

            if (!f.check(serviceItem))
                return false;

        }

        return true;

    }

}
