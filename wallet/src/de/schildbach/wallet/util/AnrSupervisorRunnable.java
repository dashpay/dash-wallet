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

import android.os.Handler;
import android.os.Looper;

import org.dash.wallet.common.services.analytics.FirebaseAnalyticsServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link Runnable} testing the UI thread every 10s until {@link
 * #stop()} is called
 */
public class AnrSupervisorRunnable implements Runnable {

    /**
     * The {@link Handler} to access the UI threads message queue
     */
    private Handler mHandler = new Handler(Looper.getMainLooper());

    private static final Logger log = LoggerFactory.getLogger(AnrSupervisorRunnable.class);


    /**
     * The stop flag
     */
    private boolean mStopped;

    /**
     * Flag indicating the stop was performed
     */
    private boolean mStopCompleted = true;

    @Override
    public void run() {
        this.mStopCompleted = false;

        // Loop until stop() was called or thread is interrupted
        while (!Thread.interrupted()) {
            try {
                // Log
                log.debug("Check for ANR...");

                // Create new callback
                AnrSupervisorCallback callback =
                        new AnrSupervisorCallback();

                // Perform test, Handler should run 
                // the callback within 1s
                synchronized (callback) {
                    this.mHandler.post(callback);
                    callback.wait(1000);

                    // Check if called
                    if (!callback.isCalled()) {
                        // Log
                        AnrException e = new AnrException(
                                this.mHandler.getLooper().getThread());

                        // TODO: check DI options or propagate up
                        new FirebaseAnalyticsServiceImpl().logError(e, null);

                        e.logProcessMap();

                        // Wait until the thread responds again
                        callback.wait();

                    } else {
                        log.debug("UI Thread responded within 1s");
                    }
                }

                // Check if stopped
                this.checkStopped();

                // Sleep for next test
                Thread.sleep(5000);

            } catch (InterruptedException e) {
                break;

            }
        }

        // Set stop completed flag
        this.mStopCompleted = true;

        // Log
        log.debug("ANR supervision stopped");
    }

    private synchronized void checkStopped()
            throws InterruptedException {
        if (this.mStopped) {
            // Wait 1000ms
            Thread.sleep(1000);


            // Break if still stopped
            if (this.mStopped) {
                throw new InterruptedException();

            }
        }
    }

    /**
     * Stops the check
     */
    synchronized void stop() {
        log.debug("Stopping...");
        this.mStopped = true;
    }

    /**
     * Stops the check
     */
    synchronized void unstopp() {
        log.debug("Revert stopping...");
        this.mStopped = false;
    }

    /**
     * Returns whether the stop is completed
     *
     * @return true if stop is completed, false if not
     */
    synchronized boolean isStopped() {
        return this.mStopCompleted;
    }
}