/*
 * Copyright 2020 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

/*
 * original source: https://medium.com/@cwurthner/detecting-anrs-e6139f475acb
 */

package de.schildbach.wallet.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Locale;
import java.util.Map;

/**
 * A {@link Exception} to represent an ANR. This {@link Exception}'s 
 * stack trace will be the current stack trace of the given 
 * {@link Thread}
 */
public class AnrException extends Exception {

    private static final Logger log = LoggerFactory.getLogger(AnrException.class);

    /**
     * Creates a new instance
     *
     * @param thread the {@link Thread} which is not repsonding
     */
    /** The thread whose stack this exception carries; not the thread that logs it. */
    private final transient Thread thread;

    public AnrException(Thread thread) {
        super("ANR detected");
        this.thread = thread;

        // Copy the Thread's stack, 
        // so the Exception seams to occure there
        this.setStackTrace(thread.getStackTrace());

    }

    /**
     * Logs the current process and all its threads
     */
    public void logProcessMap() {
        // Log only the unresponsive thread's stack, not every thread in the
        // process. The full map was ~150 threads / ~1,000 lines per detection,
        // repeated every 5 s while the UI thread stayed slow — 5,000 logcat
        // lines in five minutes on 2026-09-25, which saturated logd and made
        // the very stalls it was reporting worse (a WorkManager thread holding
        // its scheduler lock blocked in Log.println_native; main blocked behind
        // it; "No response to onStartJob"). The full map is still available
        // through printProcessMap(PrintStream) for a report that wants it.
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(bos);
        // Label the saved stack with the thread it was taken from: the cleanup
        // and send monitors build and log this from their own coroutine, so
        // Thread.currentThread() here is the monitor, not the stalled thread.
        // No thread count: Thread.getAllStackTraces() would collect every
        // live thread's stack just to size the map, every 5 s.
        ps.println("Unresponsive thread:");
        this.printThread(ps, Locale.getDefault(), thread, this.getStackTrace());
        log.info(this.getClass().getSimpleName() + " " +
                new String(bos.toByteArray()));
    }

    /**
     * Prints the current process and all its threads
     *
     * @param ps the {@link PrintStream} to which the 
     *           info is written
     */
    public void printProcessMap(PrintStream ps) {
        // Get all stack traces in the system
        Map<Thread, StackTraceElement[]> stackTraces =
                Thread.getAllStackTraces();

        ps.println("Process map:");

        for (Thread thread : stackTraces.keySet()) {
            if (stackTraces.get(thread).length > 0) {
                this.printThread(ps, Locale.getDefault(),
                        thread, stackTraces.get(thread));
                ps.println();
            }
        }
    }

    /**
     * Prints the given thread
     * @param ps the {@link PrintStream} to which the 
     *          info is written
     * @param l the {@link Locale} to use
     * @param thread the {@link Thread} to print
     * @param stack the {@link Thread}'s stack trace
     */
    private void printThread(PrintStream ps, Locale l,
                             Thread thread, StackTraceElement[] stack) {
        ps.println(String.format(l, "\t%s (%s)",
                thread.getName(), thread.getState()));

        for (StackTraceElement element : stack) {
            ps.println(String.format(l, "\t\t%s.%s(%s:%d)",
                    element.getClassName(),
                    element.getMethodName(),
                    element.getFileName(),
                    element.getLineNumber()));
        }
    }
}
