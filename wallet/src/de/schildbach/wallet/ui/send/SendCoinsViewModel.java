/*
 * Copyright 2019 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui.send;

import android.app.Application;
import android.os.Handler;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;

import javax.annotation.Nullable;

import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.data.PaymentIntent;

public class SendCoinsViewModel extends AndroidViewModel {

    public enum State {
        REQUEST_PAYMENT_REQUEST, //
        INPUT, // asks for confirmation
        DECRYPTING, SIGNING, SENDING, SENT, FAILED // sending states
    }

    public enum SendCoinsOfflineStatus {
        SUCCESS,
        INSUFFICIENT_MONEY,
        INVALID_ENCRYPTION_KEY,
        EMPTY_WALLET_FAILED,
        FAILURE
    }

    public final Wallet wallet;

    public final MutableLiveData<State> state = new MutableLiveData<>();
    public MutableLiveData<Pair<SendCoinsOfflineStatus, Object>> onSendCoinsOffline = new MutableLiveData<>();

    public PaymentIntent paymentIntent = null;

    PaymentIntent finalPaymentIntent = null;

    @Nullable
    public Transaction sentTransaction = null;
    @Nullable
    public SendRequest dryrunSendRequest = null;
    @Nullable
    public Exception dryrunException = null;


    public SendCoinsViewModel(final Application application) {
        super(application);
        WalletApplication walletApplication = (WalletApplication) application;
        wallet = walletApplication.getWallet();
    }

    void signAndSendPayment(final SendRequest sendRequest, final Handler backgroundHandler, PaymentIntent finalPaymentIntent) {
        state.setValue(State.SIGNING);
        this.finalPaymentIntent = finalPaymentIntent;

        new SendCoinsOfflineTask(wallet, backgroundHandler) {

            @Override
            protected void onSuccess(@NonNull final Transaction transaction) {
                state.setValue(State.SENDING);
                onSendCoinsOffline.setValue(
                        new Pair<>(SendCoinsOfflineStatus.SUCCESS, (Object) transaction)
                );
            }

            @Override
            protected void onInsufficientMoney(final Coin missing) {
                state.setValue(State.INPUT);
                onSendCoinsOffline.setValue(
                        new Pair<>(SendCoinsOfflineStatus.INSUFFICIENT_MONEY, (Object) missing)
                );
            }

            @Override
            protected void onInvalidEncryptionKey() {
                state.setValue(State.INPUT);
                onSendCoinsOffline.setValue(
                        new Pair<>(SendCoinsOfflineStatus.INVALID_ENCRYPTION_KEY, null)
                );
            }

            @Override
            protected void onEmptyWalletFailed() {
                state.setValue(State.INPUT);
                onSendCoinsOffline.setValue(
                        new Pair<>(SendCoinsOfflineStatus.EMPTY_WALLET_FAILED, null)
                );
            }

            @Override
            protected void onFailure(Exception exception) {
                state.setValue(State.FAILED);
                onSendCoinsOffline.setValue(
                        new Pair<>(SendCoinsOfflineStatus.FAILURE, (Object) exception)
                );
            }

        }.sendCoinsOffline(sendRequest); // send asynchronously
    }
}
