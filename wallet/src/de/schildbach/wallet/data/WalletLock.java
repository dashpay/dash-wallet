/*
 * Copyright 2015 the original author or authors.
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

package de.schildbach.wallet.data;

import org.bitcoinj.wallet.Wallet;
import org.dash.wallet.common.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class WalletLock {

    protected static final Logger log = LoggerFactory.getLogger(WalletLock.class);

    private List<OnLockChangeListener> listeners = new ArrayList<>();

    public static long DEFAULT_LOCK_TIMER_MILLIS = TimeUnit.MINUTES.toMillis(3);

    private Configuration config;

    private static WalletLock instance;

    private WalletLock() {}

    public static WalletLock getInstance() {
        if (instance == null) {
            instance = new WalletLock();
        }
        return instance;
    }

    public boolean isWalletLocked(Wallet wallet) {
        boolean isTimerExpired = config.getLastUnlockTime() + DEFAULT_LOCK_TIMER_MILLIS < System.currentTimeMillis();
        return isTimerExpired && wallet.isEncrypted();
    }

    public void setWalletLocked(boolean walletLocked) {
        log.info(walletLocked ? "Locking" : "Unlocking" + " wallet");
        if(walletLocked) {
            config.setLastUnlockTime(0);
        } else {
            config.setLastUnlockTime(System.currentTimeMillis());
        }
        for (OnLockChangeListener listener : listeners) {
            listener.onLockChanged(walletLocked);
        }
    }

    public void addListener(OnLockChangeListener listener) {
        listeners.add(listener);
    }

    public void removeListener(OnLockChangeListener listener) {
        listeners.remove(listener);
    }

    public interface OnLockChangeListener {
        void onLockChanged(boolean locked);
    }

    public void setConfiguration(Configuration config) {
        this.config = config;
    }
}
