/*
 * Copyright 2013-2015 the original author or authors.
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

package de.schildbach.wallet.payments;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.crypto.KeyCrypterException;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptException;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.Wallet.CompletionException;
import org.bitcoinj.wallet.Wallet.CouldNotAdjustDownwards;
import org.dash.wallet.common.WalletDataProvider;
import org.dash.wallet.common.services.LeftoverBalanceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.schildbach.wallet.Constants;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

/**
 * @author Andreas Schildbach
 */
public abstract class SendCoinsOfflineTask {
    private final Wallet wallet;
    private final WalletDataProvider walletData;
    private final Handler backgroundHandler;
    private final Handler callbackHandler;

    private static final Logger log = LoggerFactory.getLogger(SendCoinsOfflineTask.class);

    public SendCoinsOfflineTask(
            final Wallet wallet,
            final WalletDataProvider walletData,
            final Handler backgroundHandler
    ) {
        this.wallet = wallet;
        this.walletData = walletData;
        this.backgroundHandler = backgroundHandler;
        this.callbackHandler = new Handler(Looper.myLooper());
    }

    public final void sendCoinsOffline(
            final SendRequest sendRequest,
            final boolean txAlreadyCompleted,
            final boolean checkBalanceConditions
    ) {
        backgroundHandler.post(() -> {
            org.bitcoinj.core.Context.propagate(Constants.CONTEXT);

            try {
                if (checkBalanceConditions) {
                    checkBalanceConditions(sendRequest.tx);
                }

                log.info("sending: {}", sendRequest);
                if (txAlreadyCompleted) {
                    wallet.commitTx(sendRequest.tx);
                } else {
                    wallet.sendCoinsOffline(sendRequest);
                }
                final Transaction transaction = sendRequest.tx;
                log.info("send successful, transaction committed: {}", transaction.getTxId().toString());
                callbackHandler.post(() -> onSuccess(transaction));
            } catch (final LeftoverBalanceException ex) {
                log.info("send failed due to leftover balance check");
                callbackHandler.post(() -> onLeftoverBalanceError(ex));
            } catch (final InsufficientMoneyException x) {
                final Coin missing = x.missing;
                if (missing != null)
                    log.info("send failed, {} missing", missing.toFriendlyString());
                else
                    log.info("send failed, insufficient coins");

                callbackHandler.post(() -> onInsufficientMoney(x.missing));
            } catch (final ECKey.KeyIsEncryptedException x) {
                log.info("send failed, key is encrypted: {}", x.getMessage());
                callbackHandler.post(() -> onFailure(x));
            } catch (final KeyCrypterException x) {
                log.info("send failed, key crypter exception: {}", x.getMessage());
                final boolean isEncrypted = wallet.isEncrypted();
                callbackHandler.post(() -> {
                    if (isEncrypted)
                        onInvalidEncryptionKey();
                    else
                        onFailure(x);
                });
            } catch (final CouldNotAdjustDownwards x) {
                log.info("send failed, could not adjust downwards: {}", x.getMessage());
                callbackHandler.post(this::onEmptyWalletFailed);
            } catch (final CompletionException x) {
                log.info("send failed, cannot complete: {}", x.getMessage());
                callbackHandler.post(() -> onFailure(x));
            }
        });
    }

    protected abstract void onSuccess(Transaction transaction);

    protected abstract void onInsufficientMoney(Coin missing);

    protected abstract void onLeftoverBalanceError(@NonNull LeftoverBalanceException ex);

    protected abstract void onInvalidEncryptionKey();

    protected void onEmptyWalletFailed() {
        onFailure(new CouldNotAdjustDownwards());
    }

    protected abstract void onFailure(Exception exception);

    private void checkBalanceConditions(Transaction tx) throws LeftoverBalanceException {
        for (TransactionOutput output : tx.getOutputs()) {
            try {
                if (!output.isMine(wallet)) {
                    final Script script = output.getScriptPubKey();
                    Address address = script.getToAddress(Constants.NETWORK_PARAMETERS, true);
                    walletData.checkSendingConditions(address, output.getValue());
                    return;
                }
            } catch (final ScriptException ignored) { }
        }
    }
}
