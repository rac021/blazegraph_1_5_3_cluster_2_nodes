package com.bigdata.service.jini;

import java.util.Arrays;

import net.jini.config.Configuration;
import net.jini.config.ConfigurationException;
import net.jini.core.discovery.LookupLocator;
import net.jini.core.entry.Entry;
import net.jini.discovery.LookupDiscovery;

import org.apache.log4j.Logger;

import com.bigdata.util.NV;

/**
 * The {@link JiniClient} configuration.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class JiniClientConfig {
    
    final static protected Logger log = Logger
            .getLogger(JiniClientConfig.class);

    /**
     * {@link Configuration} options for this class.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     */
    public interface Options {
    
        /**
         * The component name for these {@link Configuration} options.
         */
        String NAMESPACE = JiniClient.class.getName();

        /**
         * {@link Entry}[] attributes used to describe the client or service.
         */
        String ENTRIES = "entries";
        
        /**
         * A {@link String}[] whose values are the group(s) to be used for
         * discovery (no default). Note that multicast discovery is always used
         * if {@link LookupDiscovery#ALL_GROUPS} (a <code>null</code>) is
         * specified. {@link LookupDiscovery#NO_GROUPS} is the symbolic constant
         * for an empty String[].
         */
        String GROUPS = "groups";

        /**
         * An array of one or more {@link LookupLocator}s specifying unicast
         * URIs of the form <code>jini://host/</code> or
         * <code>jini://host:port/</code> (no default) -or- an empty array if
         * you want to use multicast discovery <strong>and</strong> you have
         * specified {@link #GROUPS} as {@link LookupDiscovery#ALL_GROUPS} (a
         * <code>null</code>).
         */
        String LOCATORS = "locators";

        /**
         * An optional {@link NV}[] specifying properties that will be used by
         * the {@link JiniClient}.
         * <p>
         * Note: This properties are also read from the {@link Configuration}
         * using the optional service or the application {@link Class} specified
         * to the {@link JiniClientConfig} ctor. Any properties found there are
         * merged, overwriting those specified for {@link JiniClient} directly.
         * This allows both general defaults and both additional service
         * properties and service specific overrides of the general defaults.
         * 
         * @deprecated This is used by the {@link JiniClient}, not the
         *             {@link JiniClientConfig}. It's presents on this interface
         *             is therefore confusing. It should be moved to the
         *             {@link JiniClient.Options}. This symbolic constant can
         *             show up in {@link Configuration} files, so we probably
         *             need to leave in a reference here to redirect people to
         *             the {@link JiniClient.Options} interface.
         *             <p>
         *             The historical presence of this property on the
         *             {@link JiniClientConfig} class is the reason why the
         *             {@link JiniClientConfig} constructor has an ignored class
         *             name argument.
         */
        String PROPERTIES = "properties";
        
    }

    /**
     * The {@link Entry}[] and never null. This will be an empty {@link Entry}[]
     * if no {@link Entry}s were specified.
     * 
     * @see Options#ENTRIES
     */
    final public Entry[] entries;
    
    /**
     * The join group(s).
     * 
     * @see Options#GROUPS
     */
    final public String[] groups;

    /**
     * The locators.
     * 
     * @see Options#LOCATORS
     */
    final public LookupLocator[] locators;

//    final public Properties properties;
    
    public String toString() {

        return getClass().getSimpleName()//
                + "{ " + Options.GROUPS + "=" + Arrays.toString(groups)//
                + ", " + Options.LOCATORS + "=" + Arrays.toString(locators)//
                + ", " + Options.ENTRIES + "=" + Arrays.toString(entries)//
//                + ", " + Options.PROPERTIES + "=" + properties//
                + "}";

    }

    /**
     * @param classNameIsIgnored
     *            The class name of the client or service (optional). When
     *            specified, properties defined for that class in the
     *            configuration will be used and will override those specified
     *            for the {@value Options#NAMESPACE}.
     * @param config
     *            The {@link Configuration}.
     * 
     * @throws ConfigurationException
     *             if there is a problem reading the jini configuration for the
     *             client.
     * 
     * @see Options
     */
    public JiniClientConfig(final String classNameIsIgnored,
            final Configuration config) throws ConfigurationException {
        
        /*
         * Extract how the service will advertise itself from the Configuration
         * (event application clients have this information).
         */
        entries = (Entry[]) config.getEntry(Options.NAMESPACE, Options.ENTRIES,
                Entry[].class, new Entry[0]);

        /*
         * Extract how the client will discover services from the Configuration.
         */
        groups = (String[]) config.getEntry(Options.NAMESPACE, Options.GROUPS,
                String[].class
        // , LookupDiscovery.ALL_GROUPS/* default */
                );

        /*
         * Note: multicast discovery is used regardless if
         * LookupDiscovery.ALL_GROUPS is selected above.
         * 
         * @todo The default for the lookupLocators is [null] so that the
         * default "ALL_GROUPS" means that the lookupLocators are ignored.
         */
        locators = (LookupLocator[]) config.getEntry(Options.NAMESPACE,
                Options.LOCATORS, LookupLocator[].class
        //                , null/* default */
                );

        if (log.isInfoEnabled())
            log.info(toString());

    }

}
