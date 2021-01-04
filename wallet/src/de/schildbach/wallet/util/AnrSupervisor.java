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


import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A class supervising the UI thread for ANR errors. Use
 * {@link #start()} and {@link #stop()} to control
 * when the UI thread is supervised
 */
public class AnrSupervisor {
    /**
     * The {@link ExecutorService} checking the UI thread
     */
    private ExecutorService mExecutor =
            Executors.newSingleThreadExecutor();
    /**
     * The {@link AnrSupervisorRunnable} running on a separate
     * thread
     */
    private final AnrSupervisorRunnable mSupervisor =
            new AnrSupervisorRunnable();

    /**
     * Starts the supervision
     */
    public synchronized void start() {
        synchronized (this.mSupervisor) {
            if (this.mSupervisor.isStopped()) {
                this.mExecutor.execute(this.mSupervisor);

            } else {
                this.mSupervisor.unstopp();

            }
        }
    }

    /**
     * Stops the supervision. The stop is delayed, so if
     * start() is called right after stop(),
     * both methods will have no effect. There will be at least one
     * more ANR check before the supervision is stopped.
     */
    public synchronized void stop() {
        this.mSupervisor.stop();

    }
}
