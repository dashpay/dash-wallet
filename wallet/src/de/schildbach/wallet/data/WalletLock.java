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

import java.util.ArrayList;
import java.util.List;

public class WalletLock {

    private List<OnLockChangeListener> listeners = new ArrayList<>();

    private boolean walletLocked = true;

    private static WalletLock instance;

    private WalletLock() {}

    public static WalletLock getInstance() {
        if (instance == null) {
            instance = new WalletLock();
        }
        return instance;
    }

    public boolean isWalletLocked(Wallet wallet) {
        return walletLocked && wallet.isEncrypted();
    }

    public void setWalletLocked(boolean walletLocked) {
        this.walletLocked = walletLocked;
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

}
