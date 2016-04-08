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
package com.bigdata.jini.start.process;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.log4j.Logger;
import org.apache.system.SystemUtil;

import com.bigdata.BigdataStatics;
import com.bigdata.jini.start.IServiceListener;

/**
 * Helper object for a running {@link Process} that DOES NOT require any input.
 * The output of the process will be logged, but not otherwise processed.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class ProcessHelper {

    /**
     * Note: LOG @ INFO if you want to see all messages written on the console
     * by a child process, otherwise only the first N lines will be copied onto
     * the console of the parent process.
     * 
     * @see BigdataStatics#echoProcessStartupLineCount
     */
    protected static final Logger log = Logger.getLogger(ProcessHelper.class);

    /**
     * A useful name for the process.
     */
    public final String name;
    
    final IServiceListener listener;

    /**
     * The {@link Process}.
     */
    private final Process process;
    
    private final ReentrantLock lock = new ReentrantLock();
    
    /**
     * Signaled if we notice that the process has died by the thread which is
     * monitoring its output.
     */
    private final Condition dead = lock.newCondition();
    
    private final AtomicInteger exitValue = new AtomicInteger(-1);

    public String toString() {
        
        final int exitValue = this.exitValue.get();
        
        return getClass().getSimpleName() + "{name=" + name
                + (exitValue != -1 ? ", exitValue=" + exitValue : "") + "}";
        
    }
    
//    /**
//     * Return <code>true</code> iff the process is still executing.
//     */
//    public boolean isRunning() {
//
//        lock.lock();
//
//        try {
//
//            return exitValue.get() != -1;
//
//        } finally {
//
//            lock.unlock();
//
//        }
//
//    }
    
    /**
     * Await the exit value and return it when it becomes available.
     * 
     * @return The exit value.
     * 
     * @throws InterruptedException
     *             if the caller's thread is interrupted while awaiting the
     *             process exit value.
     */
    public int exitValue() throws InterruptedException {

        try {

            if(log.isInfoEnabled())
                log.info("Waiting on exitValue: " + this);

            final int exitValue = exitValue(Long.MAX_VALUE, TimeUnit.SECONDS);

            final String msg = "Process is dead: " + this + ", exitValue="
                    + exitValue;

            if (exitValue != 0) {
                // log as error w/ stack trace for abnormal exit.
                log.error(msg, new RuntimeException(msg));
            } else {
                // just log a warning for a normal exit.
                log.warn(msg);
            }

            return exitValue;
            
        } catch(TimeoutException ex) {
            
            // Note: SHOULD NOT throw a TimeoutException!
            throw new AssertionError();
            
        }
        
    }
        
    /**
     * Waits up to timeout units for the {@link Process} to terminate and then
     * returns its exit value.
     * 
     * @param timeout
     * @param unit
     * @return The exit value.
     * 
     * @throws TimeoutException
     *             if the {@link Process} is still running when the specified
     *             timeout elapsed.
     * @throws InterruptedException
     *             if the caller's thread was interrupted awaiting the exit
     *             value.
     */
    public int exitValue(final long timeout, final TimeUnit unit)
            throws TimeoutException, InterruptedException {

        final long begin = System.nanoTime();

        final long nanos = unit.toNanos(timeout);

        lock.lock();

        try {

            /*
             * Note: We will always execute the loop at least once and will
             * return the exitValue if it is already available.
             */
            while (true) {

                // Check to see if the exitValue has been assigned.
                final int exitValue = this.exitValue.get();

                if (exitValue != -1) {

                    // the exitValue has been assigned.
                    return exitValue;

                }

                final long remaining = nanos - (System.nanoTime() - begin);
                
                if (remaining <= 0)
                    throw new TimeoutException();

                dead.await(remaining, TimeUnit.NANOSECONDS);
                
            }
            
        } finally {
            
            lock.unlock();
            
        }
        
    }

    /**
     * Kill the process, blocking until it has terminated. The contract is only
     * "kill" not "destroy" - the persistent state of the process SHOULD NOT be
     * destroyed). Subclasses SHOULD override this method to request normal
     * process termination where possible.
     * <p>
     * Note: processes with child processes (including any bigdata services
     * since they start children to report OS performance counters) MUST exit
     * normally (at least under windows) or the parent process will not be able
     * to exit. Therefore it is very important to extend this method and send
     * proper notice to the process requesting that it terminate itself.
     * 
     * @param immediateShutdown
     *            processes with APIs that differentiate immediate shutdown and
     *            normal shutdown will use the appropriate behavior as selected
     *            by this parameter.
     * 
     * @return The exitValue of the process.
     * 
     * @throws InterruptedException
     *             if interrupted - the process may or may not have been killed
     *             and the listener will not have been notified.
     */
//    synchronized 
    public int kill(final boolean immediateShutdown)
            throws InterruptedException {

        log.warn(this);

        try {
            
            /*
             * Note: destroy() appears to be non-blocking. It also appears to be
             * safe to invoke on a process which has already been terminated.
             */
            
            process.destroy();

            // fall through

        } catch (Throwable t) {
        
            log.warn(this, t);

            // fall through

        }

        // wait for the process to die
        final int exitValue = exitValue();

        // notify listener.
        listener.remove(this);
        
        return exitValue;

    }
    
    /**
     * Only accept reference tests for equality.
     */
    public boolean equals(Object o) {

        if (this == o)
            return true;

        return false;
        
    }
    
    /**
     * Starts the {@link Process}, starts a {@link Thread} to consume its
     * output, and registers the {@link Process} with the
     * {@link IServiceListener}.
     * 
     * @param name
     *            A useful name for the process.
     * @param processBuilder
     *            The object used to start the {@link Process}.
     * @param running
     *            A {@link Queue} of the running {@link Process}es.
     * 
     * @throws IOException
     */
    public ProcessHelper(final String name, final ProcessBuilder processBuilder,
            final IServiceListener listener) throws IOException {

        if (name == null)
            throw new IllegalArgumentException();

        if (processBuilder == null)
            throw new IllegalArgumentException();

        if (listener == null)
            throw new IllegalArgumentException();

        this.name = name;

        // save the listener reference.
        this.listener = listener;

        log.warn("command: " + processBuilder.command());
//        log.warn("environment: "+builder.environment());
        
        /*
         * Merge stdout and stderr so that we only need one thread to drain the
         * output of the process.
         */
        processBuilder.redirectErrorStream(true);

        // start the process (it may take a bit to be really running).
        this.process = processBuilder.start();

//        /*
//         * Setup a thread that will tear down the child process if this process
//         * is shutdown politely (SIGINT versus SIGKILL).
//         * 
//         * TODO This could result in a lot of threads. It would be better to
//         * have a single thread and a concurrent weak value hash map of the
//         * child processes (or a strong hash map with tracking of the child life
//         * cycle events). The thread could then wipe out those children. (In
//         * fact, the SMS may already be doing this but it really should be
//         * encapsulated here.)
//         */
//        {
//            final Process p = this.process;
//            final Thread t = new Thread() {
//                @Override
//                public void run() {
//                    p.destroy();
//                }
//            };
//            t.setDaemon(true);
//            Runtime.getRuntime().addShutdownHook(t);
//        }

        // add to queue of running (or at any rate, started) processes.
        listener.add(ProcessHelper.this);
        
        final Thread thread = new Thread("consumeOutput: " + name) {

            public void run() {

                try {

                    // consume all output from the process.
                    consumeOutput();

                } finally {

                    // no longer running.
                    lock.lock();
                    try {

                        // ensure process is destroyed.
                        process.destroy();

                        /* Wait for the exit value from the process.
                         * 
                         * Note: throws InterruptedException.
                         */
                        exitValue.set(process.waitFor());

                        // signal so that anyone waiting will awaken.
                        dead.signalAll();

                        // remove the element from queue.
                        listener.remove(ProcessHelper.this);

                        // log event.
                        log.warn("Process destroyed: " + name);

                    } catch(InterruptedException ex) {
                        
                        log.warn("Interrupted awaiting process death: " + this);
                        
                    } finally {

                        lock.unlock();

                    }

                }

            }

        };

        /*
         * Note: the service starter can fail to exit properly if this is not a
         * daemon thread.
         */
        thread.setDaemon(true);

        thread.start();

        log.warn("Process starting: name=" + name);

        if (log.isInfoEnabled())
            log.info("cmd=" + getCommandString(processBuilder));

        if (log.isDebugEnabled())
            log.debug("env=" + processBuilder.environment());

    }

    /**
     * Consumes the output of the process, writing each line onto a
     * {@link Logger}.
     * <p>
     * Note: Normally you will see the child process output by configuring
     * logging for the child process. However, if you want to see the output of
     * the child process within the logging of <i>this</i> process, then you
     * have to raise the log level to INFO for this class.
     */
    protected void consumeOutput() {

        long nlines = 0;
        
        BufferedReader is = null; 
        try {

            is = new BufferedReader(new InputStreamReader(
                    process.getInputStream()));

            String s;

            /*
             * Note: when the process is killed, readLine() will return since
             * the stream will be closed.
             */
            boolean truncated = false;
            while ((s = is.readLine()) != null) {

                if (nlines++ < BigdataStatics.echoProcessStartupLineCount) {

                    System.out.println(name + " : " + s);

                } else if (!truncated) {

                    System.out.println(name + " : output truncated after "
                            + BigdataStatics.echoProcessStartupLineCount
                            + " lines");

                    truncated = true;
                    
                }
                
                if (log.isInfoEnabled())
                    log.info(s);

            }

        } catch (IOException ex) {

            log.error(ex, ex);

        } finally {

            if (is != null) {
                try {
                    is.close();
                } catch (IOException ex2) {
                    log.error(ex2, ex2);
                }
            }
            
        }

    }

    /**
     * Interrupts the caller's {@link Thread} if the process dies within the
     * specified timeout.
     * <p>
     * This thread can still be running after call() has returned and it can
     * cause a spurious interrupt. To avoid that you MUST cancel the thread
     * monitoring for the process death using the returned {@link Future}.
     * 
     * @param timeout
     *            The timeout.
     * @param unit
     *            The timeout unit.
     * 
     * @return The {@link Future} for the {@link Thread} awaiting the task
     *         death.
     * 
     * @todo isRunning() does not appear to be atomic with respect to the
     *       interrupt of the caller. In fact, it seems like you have to use
     *       {@link #exitValue(long, TimeUnit)} with a small timeout to verify
     *       that the process is in fact dead. That is weird. I am even forcing
     *       the wait for the exit value in this method but to no avail.
     */
    public Future interruptWhenProcessDies(final long timeout,
            final TimeUnit unit) {

        final long nanos = unit.toNanos(timeout);

        final Thread callersThread = Thread.currentThread();

        final Condition done = lock.newCondition();

        final Thread t = new Thread() {

            public void run() {

                lock.lock();

                try {

                    if (dead.await(nanos, TimeUnit.NANOSECONDS)) {

                        // @todo if anything, use process.waitFor()
//                        // force a wait until the exitValue has been set.
//                        final int exitValue = exitValue();
                        
                        if (log.isInfoEnabled())
                            log.info("Process is dead: name=" + name
                                    + ", exitValue=" + exitValue);
                        
                        // The process is dead, so interrupt the caller.
                        callersThread.interrupt();

                        // done
                        return;
                        
                    }

                    // timeout.
                    return;

                } catch (InterruptedException e) {

                    // halt.
                    return;

                } finally {

                    done.signalAll();
                    
                    lock.unlock();

                }

            }

        };

        t.setDaemon(true);

        t.start();
        
        return new Future() {

            private volatile boolean cancelled = false;
            
            public boolean cancel(boolean mayInterruptIfRunning) {
                if (t.isAlive() && mayInterruptIfRunning) {
                    t.interrupt();
                    return true;
                }
                cancelled = true;
                return false;
            }

            public Object get() throws InterruptedException, ExecutionException {
                try {
                    return get(Long.MAX_VALUE, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    // TimeoutException should not be thrown.
                    throw new AssertionError();
                }
            }

            public Object get(long timeout, TimeUnit unit)
                    throws InterruptedException, ExecutionException,
                    TimeoutException {
                lock.lock();
                try {
                    if (t.isAlive()) {
                        done.await(timeout, unit);
                    }
                } finally {
                    lock.unlock();
                }
                return null;
            }

            public boolean isCancelled() {
                return cancelled;
            }

            public boolean isDone() {
                return !t.isAlive();
            }
            
        };

    }

    /**
     * Return a {@link String} containing commands to set the environment for
     * the process.
     */
    static public String getEnvironment(final ProcessBuilder processBuilder) {
        
        final StringBuilder sb = new StringBuilder();
        
        for(Map.Entry<String,String> entry : processBuilder.environment().entrySet()) {

            if(!SystemUtil.isWindows()) {
                sb.append("export ");
            } else {
                sb.append("set ");
            }
            sb.append(entry.getKey());
            sb.append("=");
//            sb.append("\"");
            sb.append(entry.getValue());
//            sb.append("\"");
            sb.append("\n");
            
        }
    
        return sb.toString();
        
    }
    
    /**
     * Return the command line that would be executed.
     * 
     * @param processBuilder
     * 
     * @return
     */
    static public String getCommandString(final ProcessBuilder processBuilder) {
        
        final StringBuilder sb = new StringBuilder();
        
        boolean first = true;
        
        for(String s : processBuilder.command()) {
            
            if(!first) {
    
                sb.append(' ');
                
            }
            
            sb.append("\"");

            sb.append(escape(s));
            
            sb.append("\"");
            
            first = false;
            
        }
    
        return sb.toString();
        
    }

    /**
     * Escape any sequences that can not appear within a quoted context on a
     * command line.
     * 
     * @param s
     *            A string that will appear within a quoted context on a command
     *            line.
     * 
     * @return The escaped version of that string.
     */
    private static String escape(String s) {
        
        if(SystemUtil.isWindows()) {
            
            /*
             * Windows uses the sequence %XXX% for shell variable substitution.
             * However, URI encoding uses the % character as an escape sequence.
             * For example, "%20" is used for a single space character. We
             * translate the % character into the sequence %% to get it through
             * the Windows shell.
             */
            s = s.replaceAll("%", "%%");
            
        }
        
        return s;
        
    }
    
}
