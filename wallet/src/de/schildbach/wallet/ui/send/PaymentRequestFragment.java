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
import android.bluetooth.BluetoothAdapter;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import androidx.lifecycle.ViewModelProviders;

import org.bitcoin.protocols.payments.Protos;
import org.bitcoinj.protocols.payments.PaymentProtocol;
import org.dash.wallet.common.ui.DialogBuilder;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

import de.schildbach.wallet.data.PaymentIntent;
import de.schildbach.wallet.integration.android.BitcoinIntegration;
import de.schildbach.wallet.offline.DirectPaymentTask;
import de.schildbach.wallet.ui.InputParser;
import de.schildbach.wallet.ui.ProgressDialogFragment;
import de.schildbach.wallet.util.Bluetooth;
import de.schildbach.wallet.util.Nfc;
import de.schildbach.wallet_test.R;

import static org.dash.wallet.common.Constants.CHAR_CHECKMARK;

public final class PaymentRequestFragment extends SendCoinsFragment {

    private static final int REQUEST_CODE_ENABLE_BLUETOOTH_FOR_PAYMENT_REQUEST = 0;
    private static final int REQUEST_CODE_ENABLE_BLUETOOTH_FOR_DIRECT_PAYMENT = 1;

    private static final Logger log = LoggerFactory.getLogger(SendCoinsFragment.class);

    private BluetoothAdapter bluetoothAdapter;

    private TextView receivingStaticAddressView;
    private CheckBox directPaymentEnableView;
    private TextView directPaymentMessageView;

    private PaymentRequestViewModel paymentRequestViewModel;

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        paymentRequestViewModel = ViewModelProviders.of(this).get(PaymentRequestViewModel.class);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    @Override
    void initiate(Intent intent) {
        final String action = intent.getAction();
        final String mimeType = intent.getType();

        if (PaymentProtocol.MIMETYPE_PAYMENTREQUEST.equals(mimeType)) {

            if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)) {

                final NdefMessage ndefMessage = (NdefMessage) intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)[0];
                final byte[] ndefMessagePayload = Nfc.extractMimePayload(PaymentProtocol.MIMETYPE_PAYMENTREQUEST, ndefMessage);
                initStateFromPaymentRequest(mimeType, ndefMessagePayload);

            } else if (Intent.ACTION_VIEW.equals(action)) {

                final byte[] paymentRequest = BitcoinIntegration.paymentRequestFromIntent(intent);
                final Uri intentUri = intent.getData();
                if (intentUri != null) {
                    initStateFromIntentUri(mimeType, intentUri);
                } else if (paymentRequest != null) {
                    initStateFromPaymentRequest(mimeType, paymentRequest);
                } else {
                    throw new IllegalArgumentException();
                }

            }

        } else {
            super.initiate(intent);
        }
    }

    @Override
    public View onCreateView(@NotNull final LayoutInflater inflater, final ViewGroup container,
                             final Bundle savedInstanceState) {
        final View view = Objects.requireNonNull(super.onCreateView(inflater, container, savedInstanceState));

        receivingStaticAddressView = view.findViewById(R.id.send_coins_receiving_static_address);

        directPaymentEnableView = view.findViewById(R.id.send_coins_direct_payment_enable);
        directPaymentEnableView.setTypeface(ResourcesCompat.getFont(activity, R.font.montserrat_medium));
        directPaymentEnableView.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(final CompoundButton buttonView, final boolean isChecked) {
                if (getPaymentIntent().isBluetoothPaymentUrl() && isChecked && !bluetoothAdapter.isEnabled()) {
                    // ask for permission to enable bluetooth
                    startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                            REQUEST_CODE_ENABLE_BLUETOOTH_FOR_DIRECT_PAYMENT);
                }
            }
        });
        directPaymentMessageView = view.findViewById(R.id.send_coins_direct_payment_message);

        return view;
    }

    @Override
    public void onActivityResult(final int requestCode, final int resultCode, final Intent intent) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                onActivityResultResumed(requestCode, resultCode, intent);
            }
        });
    }

    private void onActivityResultResumed(final int requestCode, final int resultCode, final Intent intent) {
        if (requestCode == REQUEST_CODE_ENABLE_BLUETOOTH_FOR_PAYMENT_REQUEST) {
            if (getPaymentIntent().isBluetoothPaymentRequestUrl())
                requestPaymentRequest();
        } else if (requestCode == REQUEST_CODE_ENABLE_BLUETOOTH_FOR_DIRECT_PAYMENT) {
            if (getPaymentIntent().isBluetoothPaymentUrl())
                directPaymentEnableView.setChecked(resultCode == Activity.RESULT_OK);
        }
    }

    @Override
    protected void updateView() {
        super.updateView();

        PaymentIntent paymentIntent = getPaymentIntent();
        SendCoinsViewModel.State state = getState();

        if (paymentIntent.hasOutputs()) {
            if (paymentIntent.hasAddress()) {
                receivingStaticAddressView.setText(paymentIntent.getAddress().toBase58());
            } else {
                receivingStaticAddressView.setText(R.string.send_coins_fragment_receiving_address_complex);
            }
        }

        final boolean directPaymentVisible = paymentIntent.hasPaymentUrl() && paymentIntent.isBluetoothPaymentUrl() && (bluetoothAdapter != null);
        directPaymentEnableView.setVisibility(directPaymentVisible ? View.VISIBLE : View.GONE);
        directPaymentEnableView.setEnabled(state == SendCoinsViewModel.State.INPUT);

        if (paymentRequestViewModel.directPaymentAck != null) {
            directPaymentMessageView.setVisibility(View.VISIBLE);
            directPaymentMessageView.setText(paymentRequestViewModel.directPaymentAck
                    ? R.string.send_coins_fragment_direct_payment_ack
                    : R.string.send_coins_fragment_direct_payment_nack);
        } else {
            directPaymentMessageView.setVisibility(View.GONE);
        }
    }

    private void initStateFromPaymentRequest(final String mimeType, final byte[] input) {
        new InputParser.BinaryInputParser(mimeType, input) {
            @Override
            protected void handlePaymentIntent(final PaymentIntent paymentIntent) {
                updateStateFrom(paymentIntent);
            }

            @Override
            protected void error(final int messageResId, final Object... messageArgs) {
                dialog(activity, activityDismissListener, 0, messageResId, messageArgs);
            }
        }.parse();
    }

    @Override
    void updateStateFrom(final PaymentIntent paymentIntent) {
        super.updateStateFrom(paymentIntent);

        // delay applying state until fragment is resumed
        handler.post(new Runnable() {
            @Override
            public void run() {
                applyStateFrom(paymentIntent);
            }
        });
    }

    private void applyStateFrom(PaymentIntent paymentIntent) {

        paymentRequestViewModel.directPaymentAck = null;

        if (paymentIntent.hasPaymentRequestUrl() && paymentIntent.isBluetoothPaymentRequestUrl()) {

            if (bluetoothAdapter.isEnabled()) {
                requestPaymentRequest();
            } else {
                // ask for permission to enable bluetooth
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_CODE_ENABLE_BLUETOOTH_FOR_PAYMENT_REQUEST);
            }

        } else if (paymentIntent.hasPaymentRequestUrl() && paymentIntent.isHttpPaymentRequestUrl()) {

            requestPaymentRequest();

        } else {

            setState(SendCoinsViewModel.State.INPUT);

            specifyAmount(paymentIntent.getAmount());

            if (paymentIntent.isBluetoothPaymentUrl()) {
                directPaymentEnableView.setChecked(bluetoothAdapter != null && bluetoothAdapter.isEnabled());
            } else if (paymentIntent.isHttpPaymentUrl()) {
                directPaymentEnableView.setChecked(true);
            }

            updateView();
            handler.post(dryrunRunnable);
        }
    }

    private void requestPaymentRequest() {
        final String host;
        final PaymentIntent paymentIntent = getPaymentIntent();

        if (Bluetooth.isBluetoothUrl(paymentIntent.paymentRequestUrl)) {
            host = Bluetooth.decompressMac(Bluetooth.getBluetoothMac(paymentIntent.paymentRequestUrl));
        } else {
            host = Uri.parse(paymentIntent.paymentRequestUrl).getHost();
        }

        ProgressDialogFragment.showProgress(fragmentManager, getString(R.string.send_coins_fragment_request_payment_request_progress, host));
        setState(SendCoinsViewModel.State.REQUEST_PAYMENT_REQUEST);

        final RequestPaymentRequestTask.ResultCallback callback = new RequestPaymentRequestTask.ResultCallback() {
            @Override
            public void onPaymentIntent(final PaymentIntent paymentIntent) {
                ProgressDialogFragment.dismissProgress(fragmentManager);

                if (paymentIntent.isExtendedBy(paymentIntent)) {
                    // success
                    setState(SendCoinsViewModel.State.INPUT);
                    updateStateFrom(paymentIntent);
                    updateView();
                    handler.post(dryrunRunnable);
                } else {
                    final StringBuilder reasons = new StringBuilder();
                    if (!paymentIntent.equalsAddress(paymentIntent))
                        reasons.append("address");
                    if (!paymentIntent.equalsAmount(paymentIntent))
                        reasons.append(reasons.length() == 0 ? "" : ", ").append("amount");
                    if (reasons.length() == 0)
                        reasons.append("unknown");

                    final DialogBuilder dialog = DialogBuilder.warn(activity, R.string.send_coins_fragment_request_payment_request_failed_title);
                    dialog.setMessage(getString(R.string.send_coins_fragment_request_payment_request_wrong_signature) + "\n\n" + reasons);
                    dialog.singleDismissButton(new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(final DialogInterface dialog, final int which) {
                            handleCancel();
                        }
                    });
                    dialog.show();

                    log.info("BIP72 trust check failed: {}", reasons);
                }
            }

            @Override
            public void onFail(final int messageResId, final Object... messageArgs) {
                ProgressDialogFragment.dismissProgress(fragmentManager);

                final DialogBuilder dialog = DialogBuilder.warn(activity, R.string.send_coins_fragment_request_payment_request_failed_title);
                dialog.setMessage(getString(messageResId, messageArgs));
                dialog.setPositiveButton(R.string.button_retry, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(final DialogInterface dialog, final int which) {
                        requestPaymentRequest();
                    }
                });
                dialog.setNegativeButton(R.string.button_dismiss, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(final DialogInterface dialog, final int which) {
                        if (!paymentIntent.hasOutputs())
                            handleCancel();
                        else
                            setState(SendCoinsViewModel.State.INPUT);
                    }
                });
                dialog.show();
            }
        };

        if (!Bluetooth.isBluetoothUrl(paymentIntent.paymentRequestUrl))
            new RequestPaymentRequestTask.HttpRequestTask(backgroundHandler, callback, application.httpUserAgent())
                    .requestPaymentRequest(paymentIntent.paymentRequestUrl);
        else
            new RequestPaymentRequestTask.BluetoothRequestTask(backgroundHandler, callback, bluetoothAdapter)
                    .requestPaymentRequest(paymentIntent.paymentRequestUrl);
    }

    @Override
    void onSendCoinsOfflineTaskSuccess(Protos.Payment payment, Intent resultIntent) {
        super.onSendCoinsOfflineTaskSuccess(payment, resultIntent);
        if (directPaymentEnableView.isChecked()) {
            directPay(payment);
        }

        if (getPaymentIntent().standard == PaymentIntent.Standard.BIP70) {
            BitcoinIntegration.paymentToResult(resultIntent, payment.toByteArray());
        }
    }

    private void directPay(final Protos.Payment payment) {

        final PaymentIntent paymentIntent = getPaymentIntent();

        final DirectPaymentTask.ResultCallback callback = new DirectPaymentTask.ResultCallback() {
            @Override
            public void onResult(final boolean ack) {
                paymentRequestViewModel.directPaymentAck = ack;

                if (getState() == SendCoinsViewModel.State.SENDING)
                    setState(SendCoinsViewModel.State.SENT);

                updateView();
            }

            @Override
            public void onFail(final int messageResId, final Object... messageArgs) {
                final DialogBuilder dialog = DialogBuilder.warn(activity,
                        R.string.send_coins_fragment_direct_payment_failed_title);
                dialog.setMessage(paymentIntent.paymentUrl + "\n" + getString(messageResId, messageArgs)
                        + "\n\n" + getString(R.string.send_coins_fragment_direct_payment_failed_msg));
                dialog.setPositiveButton(R.string.button_retry, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(final DialogInterface dialog, final int which) {
                        directPay(payment);
                    }
                });
                dialog.setNegativeButton(R.string.button_dismiss, null);
                dialog.show();
            }
        };

        if (paymentIntent.isHttpPaymentUrl()) {
            new DirectPaymentTask.HttpPaymentTask(backgroundHandler, callback, paymentIntent.paymentUrl,
                    application.httpUserAgent()).send(payment);
        } else if (paymentIntent.isBluetoothPaymentUrl() && bluetoothAdapter != null
                && bluetoothAdapter.isEnabled()) {
            new DirectPaymentTask.BluetoothPaymentTask(backgroundHandler, callback, bluetoothAdapter,
                    Bluetooth.getBluetoothMac(paymentIntent.paymentUrl)).send(payment);
        }
    }
}
