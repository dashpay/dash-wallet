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

/**
 * A {@link Runnable} which calls {@link #notifyAll()} when run.
 */
class AnrSupervisorCallback implements Runnable {

    /**
     * Flag storing whether {@link #run()} was called
     */
    private boolean mCalled;

    /**
     * Creates a new instance
     */
    public AnrSupervisorCallback() {
        super();

    }

    @Override
    public synchronized void run() {
        this.mCalled = true;
        this.notifyAll();

    }

    /**
     * Returns whether {@link #run()} was called yet
     *
     * @return true if called, false if not
     */
    synchronized boolean isCalled() {
        return this.mCalled;

    }
}
