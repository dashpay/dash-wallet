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

import androidx.lifecycle.AndroidViewModel;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;

import javax.annotation.Nullable;

import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.data.BlockchainStateLiveData;
import de.schildbach.wallet.data.PaymentIntent;

public class SendCoinsViewModel extends AndroidViewModel {

    public enum State {
        REQUEST_PAYMENT_REQUEST, //
        INPUT, // asks for confirmation
        DECRYPTING, SIGNING, SENDING, SENT, FAILED // sending states
    }

    private final WalletApplication application;
    public final Wallet wallet;
    public final BlockchainStateLiveData blockchainState;

    @Nullable
    public State state = null;
    @Nullable
    public PaymentIntent paymentIntent = null;
    @Nullable
    public Transaction sentTransaction = null;
    @Nullable
    public Boolean directPaymentAck = null;
    @Nullable
    public SendRequest dryrunSendRequest = null;
    @Nullable
    public Exception dryrunException = null;

    public SendCoinsViewModel(final Application application) {
        super(application);
        this.application = (WalletApplication) application;
        this.wallet = this.application.getWallet();
        this.blockchainState = new BlockchainStateLiveData(this.application);
    }
}
