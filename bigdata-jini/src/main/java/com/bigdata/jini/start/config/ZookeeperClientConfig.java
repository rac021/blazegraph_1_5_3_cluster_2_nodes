package com.bigdata.jini.start.config;

import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.List;

import net.jini.config.Configuration;
import net.jini.config.ConfigurationException;

import org.apache.log4j.Logger;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;

import com.bigdata.jini.util.ConfigMath;

/**
 * Helper class for the {@link ZooKeeper} client configuration.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class ZookeeperClientConfig {

    final static private Logger log = Logger
            .getLogger(ZookeeperClientConfig.class);

    /**
     * Zookeeper client options.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan
     *         Thompson</a>
     * @version $Id$
     */
    public interface Options {

        /**
         * The namespace for these options.
         */
        String NAMESPACE = ZooKeeper.class.getName();

        /**
         * The root node in the zookeeper instance for the federation.
         */
        String ZROOT = "zroot";

        /**
         * The "sessionTimeout" in milliseconds for a {@link ZooKeeper} client.
         * 
         * @see ZooKeeper#ZooKeeper(String, int, org.apache.zookeeper.Watcher)
         */
        String SESSION_TIMEOUT = "sessionTimeout";

        int DEFAULT_SESSION_TIMEOUT = 5000;

        /**
         * A comma delimited list of host:port pairs for the zookeeper servers,
         * where the port is the <strong>client port</strong>.
         * 
         * <pre>
         * zoo1:2181,zoo2:2181,zoo3:2181
         * </pre>
         */
        String SERVERS = "servers";

        /**
         * The ACL that will be used to create various znodes on the behalf of
         * the federation (the default is {@link Ids#OPEN_ACL_UNSAFE}).
         */
        String ACL = "acl";
        
    }

    /**
     * The path to the zookeeper znode that is the root for all data for the
     * federation.
     * 
     * @see Options#ZROOT
     */
    public final String zroot;

    /**
     * The session timeout.
     * 
     * @see Options#SESSION_TIMEOUT
     */
    public final int sessionTimeout;

    /**
     * A comma delimited list of servers.
     * 
     * @see Options#SERVERS
     */
    public final String servers;

    /**
     * The ACL used to create znodes on the behalf of the federation.
     * 
     * @see Options#ACL
     */
    public final List<ACL> acl;
    
    /**
     * Alternative version used to configure from System properties or as set
     * via <code>-D</code>.
     * @throws ConfigurationException 
     */
    public ZookeeperClientConfig() throws ConfigurationException {
        
        // root node for federation within zookeeper.
        zroot = System.getProperty(Options.NAMESPACE+"."+Options.ZROOT);

        // session timeout.
        sessionTimeout = Integer.parseInt(System.getProperty(Options.NAMESPACE
                + "." + Options.SESSION_TIMEOUT, ""
                + Options.DEFAULT_SESSION_TIMEOUT));

        // comma separated list of zookeeper services.
        servers = System.getProperty(Options.NAMESPACE+"."+Options.SERVERS);

        if(servers == null)
            throw new ConfigurationException("Must specify: "
                    + Options.NAMESPACE + "." + Options.SERVERS);
        
        if (servers.matches("\\s")) {

            throw new ConfigurationException("Whitespace not allowed in "
                    + Options.SERVERS + " : " + servers);

        }
        
        // ACLs used to create various znodes
//        acl = Arrays.asList((ACL[]) config.getEntry(Options.NAMESPACE,
//                Options.ACL, ACL[].class,
//                // default ACL
//                Ids.OPEN_ACL_UNSAFE.toArray(new ACL[0])
//                ));
        acl = Ids.OPEN_ACL_UNSAFE; // TODO How to override?

        if(log.isInfoEnabled())
            log.info(this.toString());

    }
    
    /**
     * Reads the zookeeper client configuration from a {@link Configuration}.
     * 
     * @param config
     *            The configuration object.
     * 
     * @return The client configuration.
     * 
     * @throws ConfigurationException
     */
    public ZookeeperClientConfig(final Configuration config)
            throws ConfigurationException {

        // root node for federation within zookeeper.
        zroot = (String) config.getEntry(Options.NAMESPACE, Options.ZROOT,
                String.class);

        // session timeout.
        sessionTimeout = (Integer) config.getEntry(Options.NAMESPACE,
                Options.SESSION_TIMEOUT, Integer.TYPE,
                Options.DEFAULT_SESSION_TIMEOUT);

        // comma separated list of zookeeper services.
        servers = (String) config.getEntry(Options.NAMESPACE, Options.SERVERS,
                String.class);
        
        if (servers.matches("\\s")) {

            throw new ConfigurationException("Whitespace not allowed in "
                    + Options.SERVERS + " : " + servers);

        }
        
        // ACLs used to create various znodes.
        acl = Arrays.asList((ACL[]) config.getEntry(Options.NAMESPACE,
                Options.ACL, ACL[].class,
                // default ACL
                Ids.OPEN_ACL_UNSAFE.toArray(new ACL[0])//
                ));

        if(log.isInfoEnabled())
            log.info(this.toString());
        
    }

    public String toString() {

        return getClass().getSimpleName()//
                + "{ zroot=" + zroot//
                + ", sessionTimeout=" + sessionTimeout//
                + ", servers=" + servers//
                + ", acl=" + acl//
                + "}";

    }

    /**
     * Writes out the configuration in a manner that is compatible with
     * {@link #readConfiguration(Configuration)}.
     */
    public void writeConfiguration(Writer w) throws IOException {
        
        w.write(Options.NAMESPACE + " {\n");

        w.write(Options.ZROOT + "=" + ConfigMath.q(zroot) + ";\n");

        w.write(Options.SERVERS + "=" + ConfigMath.q(servers) + ";\n");

        w.write(Options.SESSION_TIMEOUT + "=" + sessionTimeout + ";\n");

        w.write(Options.ACL + "= new " + ACL.class.getName() + "[] {\n");

        for (ACL x : acl) {

            w.write("new " + ACL.class.getName() + "(");

            w.write(Integer.toString(x.getPerms()));

            w.write(",");

            w.write("new " + Id.class.getName() + "(");

            w.write(ConfigMath.q(x.getId().getScheme()));

            w.write(",");

            w.write(ConfigMath.q(x.getId().getId()));

            w.write(")),\n");

        }

        w.write("};\n");

        w.write("}\n");
        
    }

}
