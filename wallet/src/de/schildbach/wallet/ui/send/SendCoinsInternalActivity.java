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

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.nfc.NfcAdapter;

import de.schildbach.wallet.data.PaymentIntent;

public class SendCoinsInternalActivity extends SendCoinsActivity {

    public static final String INTENT_EXTRA_USER_AUTHORIZED = "user_authorized";

    public static final String ACTION_SEND_FROM_WALLET_URI = "de.schildbach.wallet.action.SEND_FROM_WALLET_URI";

    public static void start(final Context context, final PaymentIntent paymentIntent) {
        start(context, paymentIntent, false);
    }

    public static void start(final Context context, final PaymentIntent paymentIntent, boolean userAuthorized) {
        start(context, null, paymentIntent, userAuthorized);
    }

    public static void start(final Context context, final String action, final PaymentIntent paymentIntent, boolean userAuthorized) {
        final Intent intent = new Intent(context, SendCoinsInternalActivity.class);
        if (action != null) {
            intent.setAction(action);
        }
        intent.putExtra(INTENT_EXTRA_PAYMENT_INTENT, paymentIntent);
        intent.putExtra(INTENT_EXTRA_USER_AUTHORIZED, userAuthorized);
        context.startActivity(intent);
    }

    public static void sendFromWalletUri(final Activity callingActivity, int requestCode,
                                         final PaymentIntent paymentIntent) {
        final Intent intent = new Intent(callingActivity, SendCoinsInternalActivity.class);
        intent.setAction(ACTION_SEND_FROM_WALLET_URI);
        intent.putExtra(INTENT_EXTRA_USER_AUTHORIZED, false);
        intent.putExtra(INTENT_EXTRA_PAYMENT_INTENT, paymentIntent);
        callingActivity.startActivityForResult(intent, requestCode);
    }

    public boolean isUserAuthorized() {
        String action = getIntent().getAction();
        if (Intent.ACTION_VIEW.equals(action)) {
            return false;
        }
        if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)) {
            return false;
        }
        return getIntent().getBooleanExtra(INTENT_EXTRA_USER_AUTHORIZED, false);
    }

}
