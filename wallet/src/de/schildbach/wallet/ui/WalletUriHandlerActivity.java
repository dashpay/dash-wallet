/*
 * Copyright 2011-2015 the original author or authors.
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

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.bitcoinj.core.Address;
import org.bitcoinj.wallet.Wallet;
import org.dash.wallet.common.ui.BaseAlertDialogBuilder;
import org.dash.wallet.integration.uphold.ui.UpholdPortalFragment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.data.PaymentIntent;
import de.schildbach.wallet.integration.android.BitcoinIntegration;
import de.schildbach.wallet.ui.main.MainActivity;
import de.schildbach.wallet.ui.send.SendCoinsActivity;
import de.schildbach.wallet.ui.util.InputParser;
import de.schildbach.wallet.ui.util.WalletUri;
import de.schildbach.wallet_test.R;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;

import static org.dash.wallet.common.ui.BaseAlertDialogBuilderKt.formatString;

/**
 * The only purpose of this Activity is to handle all so called Wallet Uris
 * providing simple and convenient Inter App Communication.
 * It could not be handled directly by WalletActivity, since it is configured
 * as a singleTask and doesn't support startActivityForResult(...) pattern.
 */
public final class WalletUriHandlerActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_SEND_FROM_WALLET_URI = 1;

    private Wallet wallet;
    private static final Logger log = LoggerFactory.getLogger(WalletUriHandlerActivity.class);


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);


        WalletApplication application = (WalletApplication) getApplication();
        wallet = application.getWallet();

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(getIntent());
    }

    private void handleIntent(final Intent intent) {
        if (wallet == null) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        final String action = intent.getAction();
        final Uri intentUri = intent.getData();
        final String scheme = intentUri != null ? intentUri.getScheme() : null;

        if (Intent.ACTION_VIEW.equals(action) && Constants.WALLET_URI_SCHEME.equals(scheme)) {
            if (intentUri.getHost().equalsIgnoreCase("brokers")) {
                if (intentUri.getPath().contains("uphold")) {
                    Intent activityIntent = new Intent(this, MainActivity.class);
                    activityIntent.putExtra("uri", intentUri);
                    activityIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    activityIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    activityIntent.setAction(UpholdPortalFragment.AUTH_RESULT_ACTION);
                    LocalBroadcastManager.getInstance(this).sendBroadcast(activityIntent);
                }
                finish();
            } else {
                new InputParser.WalletUriParser(intentUri) {
                    @Override
                    protected void handlePaymentIntent(final PaymentIntent paymentIntent, boolean forceInstantSend) {
                        SendCoinsActivity.Companion.sendFromWalletUri(
                                WalletUriHandlerActivity.this, REQUEST_CODE_SEND_FROM_WALLET_URI, paymentIntent);
                    }

                    protected void handleMasterPublicKeyRequest(String sender) {
                        String confirmationMessage = getString(R.string.wallet_uri_handler_public_key_request_dialog_msg, sender);
                        showConfirmationDialog(confirmationMessage, positiveBtnClickCreateMasterKey);
                    }

                    protected void handleAddressRequest(String sender) {
                        String confirmationMessage = getString(R.string.wallet_uri_handler_address_request_dialog_msg, sender);
                        showConfirmationDialog(confirmationMessage, positiveBtnClickCreateAddress);
                    }

                    @Override
                    protected void error(Exception x, final int messageResId, final Object... messageArgs) {
                        BaseAlertDialogBuilder baseAlertDialogBuilder = new BaseAlertDialogBuilder(WalletUriHandlerActivity.this);
                        baseAlertDialogBuilder.setMessage(formatString(WalletUriHandlerActivity.this, messageResId, messageArgs));
                        baseAlertDialogBuilder.setNeutralText(getString(R.string.button_dismiss));
                        baseAlertDialogBuilder.setNeutralAction(
                                () -> {
                                    WalletUriHandlerActivity.this.finish();
                                    return Unit.INSTANCE;
                                }
                        );
                    }
                }.parse();
            }
        }
    }

    private String getAppName() {
        ApplicationInfo applicationInfo = getApplicationInfo();
        int stringId = applicationInfo.labelRes;
        return stringId == 0 ? applicationInfo.nonLocalizedLabel.toString() : getString(stringId);
    }

    private void showConfirmationDialog(String message, final Function0<Unit> onPositiveButtonClickListener) {
        BaseAlertDialogBuilder confirmationAlertDialogBuilder = new BaseAlertDialogBuilder(this);
        confirmationAlertDialogBuilder.setTitle(getString(R.string.app_name));
        confirmationAlertDialogBuilder.setMessage(message);
        confirmationAlertDialogBuilder.setPositiveText(getString(R.string.button_ok));
        confirmationAlertDialogBuilder.setPositiveAction(onPositiveButtonClickListener);
        confirmationAlertDialogBuilder.setNegativeText( getString(R.string.button_cancel));
        confirmationAlertDialogBuilder.setNegativeAction(negativeButtonClickListener);
        confirmationAlertDialogBuilder.buildAlertDialog().show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_SEND_FROM_WALLET_URI) {
            Intent result = null;
            if (resultCode == Activity.RESULT_OK) {
                Uri requestData = getIntent().getData();
                String transactionHash = BitcoinIntegration.transactionHashFromResult(data);
                result = WalletUri.createPaymentResult(requestData, transactionHash);
            }
            setResult(resultCode, result);
            finish();
        }
    }

    private final Function0<Unit> negativeButtonClickListener = () -> {
        WalletUriHandlerActivity.this.setResult(RESULT_CANCELED);
        WalletUriHandlerActivity.this.finish();
        return Unit.INSTANCE;
    };

    private final Function0<Unit> positiveBtnClickCreateMasterKey = () -> {
        String watchingKey = wallet.getWatchingKey().serializePubB58(wallet.getNetworkParameters());
        Uri requestData = getIntent().getData();
        Intent result = WalletUri.createMasterPublicKeyResult(requestData, watchingKey, null, getAppName());
        setResult(RESULT_OK, result);
        finish();
        return Unit.INSTANCE;
    };

    private final Function0<Unit> positiveBtnClickCreateAddress = () -> {
        Address address = wallet.freshReceiveAddress();
        Uri requestData = getIntent().getData();
        Intent result = WalletUri.createAddressResult(requestData, address.toString(), getAppName());
        setResult(RESULT_OK, result);
        finish();
        return Unit.INSTANCE;
    };
}
