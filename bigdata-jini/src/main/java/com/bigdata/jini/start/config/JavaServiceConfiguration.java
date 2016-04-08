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

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import net.jini.config.Configuration;
import net.jini.config.ConfigurationException;

import org.apache.zookeeper.data.ACL;

import com.bigdata.jini.start.IServiceListener;
import com.bigdata.jini.start.process.ProcessHelper;

/**
 * A service that is implemented in java and started directly using java. The
 * value of the "jvmargs" property in the <code>com.bigdata.jini.start</code>
 * component will be combined with the "args" property for the specific service.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class JavaServiceConfiguration extends ServiceConfiguration {

    /**
     * 
     */
    private static final long serialVersionUID = 3688535928764283524L;

    /**
     * Additional options understood by the {@link JavaServiceConfiguration}.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     * @version $Id$
     */
    public interface Options extends ServiceConfiguration.Options {

        /**
         * The path to the java executable (optional). This may be used to
         * specify a specific JVM on either a global or a per-service type
         * basis.
         * <p>
         * Note: {@link ACL}s may be used to restrict who can set this
         * property.
         */
        String JAVA_HOME = "javaHome";

        /**
         * Command line arguments represented as a {@link String}[] that will
         * be interpreted as arguments to the JVM when starting a new service
         * instance using a {@link JavaServiceConfiguration}.
         * <p>
         * This options is only permitted as a global default (e.g., "-server"
         * or "-ea" make sense for all JVMs).
         */
        String DEFAULT_JAVA_ARGS = "defaultJavaArgs";

        /**
         * CLASSPATH components represented as a {@link String}[] that will be
         * included in any {@link JavaServiceConfiguration} before any service
         * specific classpath components.
         * <p>
         * Note: When NOT specified the classpath of the current JVM will be
         * used. If you specify ANY value for this property, then the classpath
         * of the JVM WILL NOT be passed onto to the child process.
         * <p>
         * Note: The JVM will be started in the service directory.  Therefore
         * you MUST use absolute path names to locate files outside of that
         * directory!
         */
        String CLASSPATH = "classpath";

        /**
         * The default log4j configuration for {@link JavaServiceConfiguration}
         * service instances. This may be overridden on a per-service type basis.
         * It is required for {@link BigdataServiceConfiguration}s.
         * <p>
         * Note: The JVM will be started in the service directory.  Therefore
         * you MUST use an absolute URL to locate the log4j configuration file!
         */
        String LOG4J = "log4j";
        
        /**
         * The name of the class whose <code>main(String[] args)</code> method 
         * will be executed.  This is optional and defaults to the name of the
         * service configuration component.
         * 
         * @see JavaServiceConfiguration#getClassName(String, Configuration)
         */
        String CLASS_NAME = "className";

    }

    /**
     * The JAVA_HOME directory.
     * 
     * @see Options#JAVA_HOME
     */
    public final File javaHome;

    /**
     * Default JVM command line arguments.
     * 
     * @see Options#DEFAULT_JAVA_ARGS
     */
    public final String[] defaultJavaArgs;

    /**
     * The log4j URI (if specified and otherwise <code>null</code>).
     * 
     * @see Options#LOG4J
     */
    public final String log4j;

    /**
     * Optional classpath override and otherwise <code>null</code>.
     * 
     * @see Options#CLASSPATH
     */
    public final String[] classpath;

    protected void toString(final StringBuilder sb) {

        super.toString(sb);

        sb.append(", " + Options.JAVA_HOME + "=" + javaHome);

        sb.append(", " + Options.DEFAULT_JAVA_ARGS + "="
                + Arrays.toString(defaultJavaArgs));

        sb.append(", " + Options.LOG4J + "=" + log4j);

        sb.append(", " + Options.CLASSPATH + "=" + Arrays.toString(classpath));

    }

    /**
     *
     * @param component
     *            The component (service configuration). 
     * @param config
     *            The {@link Configuration}.
     * @throws ConfigurationException
     */
    public JavaServiceConfiguration(final String component,
            final Configuration config) throws ConfigurationException {

        super(getClassName(component, config), config);

        this.javaHome = getJavaHome(component, config);

        this.defaultJavaArgs = getDefaultJavaArgs(component, config);

        this.log4j = getLog4j(component, config);

        this.classpath = getClasspath(component, config);

    }

     public JavaServiceStarter newServiceStarter(IServiceListener listener)
            throws Exception {

        return new JavaServiceStarter(listener);

    }

    /**
     * Starts a java service.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     * @version $Id$
     * @param <V>
     */
    public class JavaServiceStarter<V extends ProcessHelper> extends
            AbstractServiceStarter<V> {

        /**
         * The class for the service that we are going to start.
         */
        public final Class cls;

        /**
         * @param fed
         */
        protected JavaServiceStarter(final IServiceListener listener) {

            super(listener);

            try {

                cls = Class.forName(className);

            } catch (Exception t) {

                throw new RuntimeException("className=" + className, t);

            }

        }

        /**
         * Puts the parent's CLASSPATH into the child's environment unless
         * {@link Options#CLASSPATH} was specified.
         * 
         * @param env
         */
        @Override
        protected void setUpEnvironment(final Map<String, String> env) {

            super.setUpEnvironment(env);

            if (classpath == null) {

                // pass on our classpath to the child.
                env.put("CLASSPATH", System.getProperty("java.class.path"));

            }

        }

        /**
         * Extended to ensure that the {@link #serviceDir} exists.
         */
        @Override
        protected void setUp() throws Exception {

            super.setUp();
            
        }
        
        /**
         * Adds the path of the java executable. This is based on the configured
         * value of {@link Options#JAVA_HOME}.
         * 
         * @see Options#JAVA_HOME
         */
        @Override
        protected void addCommand(final List<String> cmds) {

            cmds.add(new File(new File(javaHome, "bin"), "java").toString());

        }

        /**
         * Extended to add {@link Options#DEFAULT_JAVA_ARGS},
         * {@link Options#CLASSPATH}, {@link Options#LOG4J}, and the
         * {@link ServiceConfiguration#className} to the command line.
         * 
         * @param cmds
         */
        @Override
        protected void addCommandArgs(final List<String> cmds) {

            /*
             * Add optional properties to be specified to java on the command
             * line, e.g., the heap size, etc (added before args[] so that the
             * latter will override the defaults).
             */
            for (String arg : defaultJavaArgs) {

                cmds.add(arg);

            }

            /*
             * args[]
             */
            super.addCommandArgs(cmds);
            
            /*
             * Optional classpath
             * 
             * Note: This OVERRIDES classpath of the current JVM when it is
             * specified.
             */
            if (classpath != null) {

                /*
                 * When [classpath] is specified, we explicitly set that command
                 * line argument.
                 */

                cmds.add("-cp");

                final StringBuilder sb = new StringBuilder();

                for (String e : classpath) {

                    if (sb.length() > 0)
                        sb.append(File.pathSeparatorChar);

                    sb.append(e);

                }

                cmds.add(sb.toString());

            }

            if (log4j != null) {

                cmds.add("-Dlog4j.configuration=" + log4j);

            }

            // the class to be executed.
            cmds.add(className);

        }

    }

    /**
     * Extract the value of the {@link Options#CLASS_NAME} from the
     * configuration.
     * 
     * @param component
     *            The name of the component (service configuration).
     * @param config
     *            The configuration.
     *            
     * @return The name of the main class.
     * 
     * @throws ConfigurationException
     * 
     * @see {@link Options#CLASS_NAME}
     */
    public static String getClassName(final String component,
            final Configuration config) throws ConfigurationException {

        return (String) config.getEntry(component, Options.CLASS_NAME,
                String.class, component/* defaultValue */);

    }

    /**
     * Return the configured JAVA_HOME property value or the current value as
     * reported by the <code>java.home</code> system property if the value was
     * not explicitly configured.
     * 
     * @param component
     * @param config
     * @return
     * @throws ConfigurationException
     */
    public static File getJavaHome(final String component,
            final Configuration config) throws ConfigurationException {

        File javaHome = (File) config.getEntry(component, Options.JAVA_HOME,
                File.class, null/* defaultValue */);

        if (javaHome == null) {

            javaHome = (File) config.getEntry(Options.NAMESPACE,
                    Options.JAVA_HOME, File.class, new File(System
                            .getProperty("java.home")));

        }

        return javaHome;

    }

    /**
     * Return the log4j configuration URI for this service type.
     * 
     * @param component
     *            Identifies the service configuration.
     * @param config
     * 
     * @return The service specific log4j configuration URI or the global log4j
     *         configuration URI if none was specified for this service and
     *         <code>null</code> if no global entry was specified.
     * 
     * @throws ConfigurationException
     * 
     * @see Options#LOG4J
     */
    public static String getLog4j(final String component,
            final Configuration config) throws ConfigurationException {

        String log4j = (String) config.getEntry(component, Options.LOG4J,
                String.class, null/* defaultValue */);

        if (log4j == null) {

            log4j = (String) config.getEntry(Options.NAMESPACE, Options.LOG4J,
                    String.class, null/* defaultValue */);

        }

        return log4j;

    }

    public static String[] getDefaultJavaArgs(String className,
            Configuration config) throws ConfigurationException {

        if (config.getEntry(className, Options.DEFAULT_JAVA_ARGS,
                String[].class, null) != null) {

            throw new ConfigurationException("Only permitted in global scope: "
                    + Options.DEFAULT_JAVA_ARGS);

        }

        return (String[]) config.getEntry(Options.NAMESPACE,
                Options.DEFAULT_JAVA_ARGS, String[].class, new String[] {});

    }

    public static String[] getClasspath(String className, Configuration config)
            throws ConfigurationException {

        return getStringArray(Options.CLASSPATH, className, config, null/* defaultValue */);

    }

}
