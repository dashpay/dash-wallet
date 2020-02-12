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

package de.schildbach.wallet.ui.send;

import android.os.Handler;
import android.os.Looper;

import org.bitcoinj.core.ECKey;
import org.bitcoinj.crypto.BIP38PrivateKey;
import org.bitcoinj.crypto.KeyCrypter;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.Wallet;
import org.bouncycastle.crypto.params.KeyParameter;

/**
 * @author Andreas Schildbach
 */
public abstract class DecryptSeedTask {
    private final Handler backgroundHandler;
    private final Handler callbackHandler;

    public DecryptSeedTask(final Handler backgroundHandler) {
        this.backgroundHandler = backgroundHandler;
        this.callbackHandler = new Handler(Looper.myLooper());
    }

    public final void decryptSeed(final DeterministicSeed seed, final KeyCrypter keyCrypter, final KeyParameter keyParameter) {
        backgroundHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    final DeterministicSeed decryptedSeed = seed.decrypt(keyCrypter, null, keyParameter); // takes time

                    callbackHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            onSuccess(decryptedSeed);
                        }
                    });
                } catch (final Exception x) {
                    callbackHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            onBadPassphrase();
                        }
                    });
                }
            }
        });
    }

    protected abstract void onSuccess(DeterministicSeed decryptedSeed);

    protected abstract void onBadPassphrase();
}
