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

import org.bitcoinj.core.ECKey;
import org.bitcoinj.crypto.BIP38PrivateKey;
import org.dashj.bls.Utils;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * @author Andreas Schildbach
 */
public abstract class DecodePrivateKeyTask {
    private final Handler backgroundHandler;
    private final Handler callbackHandler;

    public DecodePrivateKeyTask(final Handler backgroundHandler) {
        this.backgroundHandler = backgroundHandler;
        this.callbackHandler = new Handler(Looper.myLooper());
    }

    public final void decodePrivateKey(final BIP38PrivateKey encryptedKey, final String passphrase) {
        backgroundHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    final ECKey decryptedKey = encryptedKey.decrypt(passphrase); // takes time

                    callbackHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            onSuccess(decryptedKey);
                        }
                    });
                } catch (final BIP38PrivateKey.BadPassphraseException x) {
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

    protected abstract void onSuccess(ECKey decryptedKey);

    protected abstract void onBadPassphrase();
}
