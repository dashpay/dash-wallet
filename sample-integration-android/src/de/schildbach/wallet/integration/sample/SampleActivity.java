/**
 * Copyright 2012-2015 the original author or authors.
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

package de.schildbach.wallet.integration.sample;

import org.bitcoin.protocols.payments.Protos;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.script.ScriptBuilder;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.TypefaceSpan;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import com.google.protobuf.ByteString;

import de.schildbach.wallet.integration.android.BitcoinIntegration;

/**
 * @author Andreas Schildbach
 */
public class SampleActivity extends Activity {
    private static final long AMOUNT = 500000;
    private static final String[] DONATION_ADDRESSES_MAINNET = {
            "XmCwQUBbu9oHnqR7w7L8C1tiTRXCQZgWo1",  //Hash Engineering donation DASH address
            "XmCwQUBbu9oHnqR7w7L8C1tiTRXCQZgWo1"
    };
    private static final String[] DONATION_ADDRESSES_TESTNET = {
            "yP8A3cbdxRtLRduy5mXDsBnJtMzHWs6ZXr",   // https://testnet-faucet.dash.org/
            "yP8A3cbdxRtLRduy5mXDsBnJtMzHWs6ZXr"    // https://testnet-faucet.dash.org/
    };
    private static final String MEMO = "Sample donation";
    private static final String WALLET_URI_SCHEME = "dashwallet";
    private static final int REQUEST_CODE = 0;
    private static final int REQUEST_PAYMENT = 1;
    private static final int REQUEST_PUBLIC_KEY = 2;
    private static final int REQUEST_ADDRESS = 3;

    private Button donateButton, requestButton, walletUriRequestButton;
    private TextView donateMessage;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.sample_activity);

        donateButton = (Button) findViewById(R.id.sample_donate_button);
        donateButton.setOnClickListener(new OnClickListener() {
            public void onClick(final View v) {
                handleDonate();
            }
        });

        requestButton = (Button) findViewById(R.id.sample_request_button);
        requestButton.setOnClickListener(new OnClickListener() {
            public void onClick(final View v) {
                handleRequest();
            }
        });

        findViewById(R.id.sample_send_payment_request).setOnClickListener(new OnClickListener() {
            public void onClick(final View v) {
                handlePaymentRequest();
            }
        });

        findViewById(R.id.sample_send_public_key_request).setOnClickListener(new OnClickListener() {
            public void onClick(final View v) {
                handlePublicKeyRequestRequest();
            }
        });

        findViewById(R.id.sample_send_address_request).setOnClickListener(new OnClickListener() {
            public void onClick(final View v) {
                handleAddressForPaymentRequest();
            }
        });

        donateMessage = (TextView) findViewById(R.id.sample_donate_message);
    }

    private String[] donationAddresses() {
        final boolean isMainnet = ((RadioButton) findViewById(R.id.sample_network_mainnet)).isChecked();

        return isMainnet ? DONATION_ADDRESSES_MAINNET : DONATION_ADDRESSES_TESTNET;
    }

    private void handleDonate() {
        final String[] addresses = donationAddresses();

        BitcoinIntegration.requestForResult(SampleActivity.this, REQUEST_CODE, addresses[0]);
    }

    private void handleRequest() {
        try {
            final String[] addresses = donationAddresses();
            final NetworkParameters params = Address.getParametersFromAddress(addresses[0]);

            final Protos.Output.Builder output1 = Protos.Output.newBuilder();
            output1.setAmount(AMOUNT);
            output1.setScript(ByteString
                    .copyFrom(ScriptBuilder.createOutputScript(Address.fromString(params, addresses[0])).getProgram()));

            final Protos.Output.Builder output2 = Protos.Output.newBuilder();
            output2.setAmount(AMOUNT);
            output2.setScript(ByteString
                    .copyFrom(ScriptBuilder.createOutputScript(Address.fromString(params, addresses[1])).getProgram()));

            final Protos.PaymentDetails.Builder paymentDetails = Protos.PaymentDetails.newBuilder();
            paymentDetails.setNetwork(params.getPaymentProtocolId());
            paymentDetails.addOutputs(output1);
            paymentDetails.addOutputs(output2);
            paymentDetails.setMemo(MEMO);
            paymentDetails.setTime(System.currentTimeMillis());

            final Protos.PaymentRequest.Builder paymentRequest = Protos.PaymentRequest.newBuilder();
            paymentRequest.setSerializedPaymentDetails(paymentDetails.build().toByteString());

            BitcoinIntegration.requestForResult(SampleActivity.this, REQUEST_CODE,
                    paymentRequest.build().toByteArray());
        } catch (final AddressFormatException x) {
            throw new RuntimeException(x);
        }
    }

    private void handlePaymentRequest() {
        Uri requestUri = new Uri.Builder()
                .scheme(WALLET_URI_SCHEME)
                .authority("")
                .appendQueryParameter("pay", donationAddresses()[0])
                .appendQueryParameter("amount", "0.001")
                //.appendQueryParameter("req-IS", "1")
                .appendQueryParameter("sender", getAppName())
                .build();
        sendInterAppCommunicationRequest(requestUri, REQUEST_PAYMENT);
    }

    private void handlePublicKeyRequestRequest() {
        Uri requestUri = new Uri.Builder()
                .scheme(WALLET_URI_SCHEME)
                .authority("")
                .appendQueryParameter("request", "masterPublicKey")
                .appendQueryParameter("account", "0")
                .appendQueryParameter("sender", getAppName())
                .build();
        sendInterAppCommunicationRequest(requestUri, REQUEST_PUBLIC_KEY);
    }

    private void handleAddressForPaymentRequest() {
        Uri requestUri = new Uri.Builder()
                .scheme(WALLET_URI_SCHEME)
                .authority("")
                .appendQueryParameter("request", "address")
                .appendQueryParameter("sender", getAppName())
                .build();
        sendInterAppCommunicationRequest(requestUri, REQUEST_ADDRESS);
    }

    private void sendInterAppCommunicationRequest(Uri requestUri, int requestCode) {
        Intent walletUriIntent = new Intent(Intent.ACTION_VIEW, requestUri);
        ComponentName componentName = walletUriIntent.resolveActivity(getPackageManager());
        if (componentName != null) {
            Intent chooserIntent = Intent.createChooser(walletUriIntent, "Select Wallet");
            startActivityForResult(chooserIntent, requestCode);
        } else {
            Toast.makeText(this, "Dash Wallet not installed", Toast.LENGTH_LONG).show();
        }
    }

    private String getAppName() {
        ApplicationInfo applicationInfo = getApplicationInfo();
        int stringId = applicationInfo.labelRes;
        return stringId == 0 ? applicationInfo.nonLocalizedLabel.toString() : getString(stringId);
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        if (requestCode == REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                final String txHash = BitcoinIntegration.transactionHashFromResult(data);
                if (txHash != null) {
                    final SpannableStringBuilder messageBuilder = new SpannableStringBuilder("Transaction hash:\n");
                    messageBuilder.append(txHash);
                    messageBuilder.setSpan(new TypefaceSpan("monospace"), messageBuilder.length() - txHash.length(),
                            messageBuilder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

                    if (BitcoinIntegration.paymentFromResult(data) != null)
                        messageBuilder.append("\n(also a BIP70 payment message was received)");

                    donateMessage.setText(messageBuilder);
                    donateMessage.setVisibility(View.VISIBLE);
                }

                Toast.makeText(this, "Thank you!", Toast.LENGTH_LONG).show();
            } else if (resultCode == Activity.RESULT_CANCELED) {
                Toast.makeText(this, "Cancelled.", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Unknown result.", Toast.LENGTH_LONG).show();
            }
        } else if (resultCode == Activity.RESULT_OK) {
            Uri resultData = data.getData();
            String message;
            if (resultData != null) {
                if (requestCode == REQUEST_PAYMENT) {
                    String callback = resultData.getQueryParameter("callback");
                    String address = resultData.getQueryParameter("address");
                    String txid = resultData.getQueryParameter("txid");
                    message = String.format("callback: %s\naddress: %s\ntxid: %s", callback, address, txid);
                } else if (requestCode == REQUEST_PUBLIC_KEY) {
                    String callback = resultData.getQueryParameter("callback");
                    String masterPublicKeyBip32 = resultData.getQueryParameter("masterPublicKeyBIP32");
                    String masterPublicKeyBip44 = resultData.getQueryParameter("masterPublicKeyBIP44");
                    String source = resultData.getQueryParameter("source");
                    message = String.format("%s\n%s\n%s\n%s", callback, masterPublicKeyBip32, masterPublicKeyBip44, source);
                } else if (requestCode == REQUEST_ADDRESS) {
                    String callback = resultData.getQueryParameter("callback");
                    String address = resultData.getQueryParameter("address");
                    String source = resultData.getQueryParameter("source");
                    message = String.format("%s\n%s\n%s", callback, address, source);
                } else {
                    message = "Invalid requestCode" + requestCode;
                }
            } else {
                message = "Error: result data is empty";
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "Operation canceled", Toast.LENGTH_LONG).show();
        }
    }
}
