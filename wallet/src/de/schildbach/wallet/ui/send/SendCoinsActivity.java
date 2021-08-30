/*
 * Copyright 2020 Dash Core Group
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

import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.view.MenuItem;

import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProviders;

import org.bitcoinj.core.CoinDefinition;
import org.bitcoinj.protocols.payments.PaymentProtocol;
import org.dash.wallet.common.ui.DialogBuilder;

import de.schildbach.wallet.data.PaymentIntent;
import de.schildbach.wallet.integration.android.BitcoinIntegration;
import de.schildbach.wallet.livedata.Resource;
import de.schildbach.wallet.ui.AbstractBindServiceActivity;
import de.schildbach.wallet.ui.LockScreenActivity;
import de.schildbach.wallet.util.Nfc;
import de.schildbach.wallet_test.R;

public class SendCoinsActivity extends AbstractBindServiceActivity {

    public static final String ANYPAY_SCHEME = "pay";

    public static final String INTENT_EXTRA_PAYMENT_INTENT = "payment_intent";

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getIntent().putExtra(LockScreenActivity.INTENT_EXTRA_KEEP_UNLOCKED, true);

        SendCoinsActivityViewModel viewModel = ViewModelProviders.of(this).get(SendCoinsActivityViewModel.class);
        viewModel.getBasePaymentIntent().observe(this, new Observer<Resource<PaymentIntent>>() {
            @Override
            public void onChanged(Resource<PaymentIntent> paymentIntentResource) {
                switch (paymentIntentResource.getStatus()) {
                    case LOADING: {

                        break;
                    }
                    case ERROR: {
                        String message = paymentIntentResource.getMessage();
                        if (message != null) {
                            final DialogBuilder dialog = new DialogBuilder(SendCoinsActivity.this);
                            dialog.setMessage(message);
                            dialog.singleDismissButton(activityDismissListener);
                            dialog.show();
                        }
                        break;
                    }
                    case SUCCESS: {
                        if (paymentIntentResource.getData() != null) {
                            initStateFromPaymentIntent(paymentIntentResource.getData());
                        }
                        break;
                    }
                }
            }
        });

        if (savedInstanceState == null) {
            final Intent intent = getIntent();
            final String action = intent.getAction();
            final Uri intentUri = intent.getData();
            final String scheme = intentUri != null ? intentUri.getScheme() : null;
            final String mimeType = intent.getType();

            if ((Intent.ACTION_VIEW.equals(action) || NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action))
                    && intentUri != null && (CoinDefinition.coinURIScheme.equals(scheme) || ANYPAY_SCHEME.equals(scheme))) {
                viewModel.initStateFromDashUri(intentUri);

            } else if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action) && PaymentProtocol.MIMETYPE_PAYMENTREQUEST.equals(mimeType)) {

                final NdefMessage ndefMessage = (NdefMessage) intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)[0];
                final byte[] ndefMessagePayload = Nfc.extractMimePayload(PaymentProtocol.MIMETYPE_PAYMENTREQUEST, ndefMessage);

                viewModel.initStateFromPaymentRequest(mimeType, ndefMessagePayload);

            } else if (Intent.ACTION_VIEW.equals(action) && PaymentProtocol.MIMETYPE_PAYMENTREQUEST.equals(mimeType)) {

                final byte[] paymentRequest = BitcoinIntegration.paymentRequestFromIntent(intent);
                if (intentUri != null) {

                    viewModel.initStateFromIntentUri(mimeType, intentUri);

                } else if (paymentRequest != null) {

                    viewModel.initStateFromPaymentRequest(mimeType, paymentRequest);

                } else {

                    throw new IllegalArgumentException();
                }

            } else if (intent.hasExtra(SendCoinsActivity.INTENT_EXTRA_PAYMENT_INTENT)) {

                final PaymentIntent paymentIntent = intent.getParcelableExtra(SendCoinsActivity.INTENT_EXTRA_PAYMENT_INTENT);
                initStateFromPaymentIntent(paymentIntent);

            } else {

                throw new IllegalStateException();
            }
        }

        getWalletApplication().startBlockchainService(false);
    }

    private void initStateFromPaymentIntent(PaymentIntent paymentIntent) {
        if (paymentIntent.hasPaymentRequestUrl()) {
            showSendPaymentProtocol(paymentIntent);
        } else {
            showSendFragment(paymentIntent);
        }
    }

    private void showSendFragment(PaymentIntent paymentIntent) {
        getIntent().putExtra(SendCoinsActivity.INTENT_EXTRA_PAYMENT_INTENT, paymentIntent);
        setContentView(R.layout.send_coins_content);
    }

    private void showSendPaymentProtocol(PaymentIntent paymentIntent) {
        setContentView(R.layout.activity_payment_protocol);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.container, PaymentProtocolFragment.newInstance(paymentIntent))
                .commitNow();
    }

    private final DialogInterface.OnClickListener activityDismissListener = new DialogInterface.OnClickListener() {
        @Override
        public void onClick(final DialogInterface dialog, final int which) {
            finish();
        }
    };

    public boolean isUserAuthorized() {
        return false;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}
