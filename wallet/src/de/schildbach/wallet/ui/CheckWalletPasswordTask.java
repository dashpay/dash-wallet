/*
 * Copyright 2014-2015 the original author or authors.
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

package de.schildbach.wallet.ui;

import android.os.Handler;
import android.os.Looper;

import org.bitcoinj.core.ECKey;
import org.bitcoinj.crypto.BIP38PrivateKey;
import org.bitcoinj.wallet.Wallet;

public abstract class CheckWalletPasswordTask {
    private final Handler backgroundHandler;
    private final Handler callbackHandler;

    public CheckWalletPasswordTask(final Handler backgroundHandler) {
        this.backgroundHandler = backgroundHandler;
        this.callbackHandler = new Handler(Looper.myLooper());
    }

    public final void checkPassword(final Wallet wallet, final String password) {
        backgroundHandler.post(new Runnable() {
            @Override
            public void run() {
                if (wallet.checkPassword(password)) {
                    callbackHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            onSuccess();
                        }
                    });
                } else {
                    callbackHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            onBadPassword();
                        }
                    });
                }
            }
        });
    }

    protected abstract void onSuccess();

    protected abstract void onBadPassword();

}
