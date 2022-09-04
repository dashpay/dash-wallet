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

package de.schildbach.wallet.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import org.bitcoinj.core.PrefixedChecksummedBytes;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.VerificationException;
import org.dash.wallet.common.ui.BaseAlertDialogBuilder;

import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.data.PaymentIntent;
import de.schildbach.wallet.ui.InputParser.StringInputParser;
import de.schildbach.wallet.ui.scan.ScanActivity;
import de.schildbach.wallet.ui.send.SendCoinsInternalActivity;
import de.schildbach.wallet.ui.send.SweepWalletActivity;
import de.schildbach.wallet_test.R;
import kotlin.Unit;

import static org.dash.wallet.common.ui.BaseAlertDialogBuilderKt.formatString;

/**
 * @author Andreas Schildbach
 */
public class SendCoinsQrActivity extends ShortcutComponentActivity {

    private static final String EXTRA_QUICK_SCAN = "extra_quick_scan";

    protected static final int REQUEST_CODE_SCAN = 0;

    public static Intent createIntent(Context context, boolean immediateScan) {
        Intent intent = new Intent(context, SendCoinsQrActivity.class);
        intent.putExtra(EXTRA_QUICK_SCAN, immediateScan);
        return intent;
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (finishIfNotInitialized()) {
            return;
        }
        getIntent().putExtra(LockScreenActivity.INTENT_EXTRA_KEEP_UNLOCKED, true);
        if (savedInstanceState == null && isQuickScan()) {
            performScanning(null);
        }
    }

    protected void performScanning(View clickView) {
        ScanActivity.startForResult(this, clickView, REQUEST_CODE_SCAN);
    }

    private boolean isQuickScan() {
        return getIntent().getBooleanExtra(EXTRA_QUICK_SCAN, false);
    }

    @Override
    public void onActivityResult(final int requestCode, final int resultCode, final Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode == REQUEST_CODE_SCAN && resultCode == Activity.RESULT_OK) {
            final String input = intent.getStringExtra(ScanActivity.INTENT_EXTRA_RESULT);

            new StringInputParser(input, true) {
                @Override
                protected void handlePaymentIntent(final PaymentIntent paymentIntent) {
                    boolean quickScan = isQuickScan();
                    // if this is a quick scan, keepUnlock = true for SendCoinsInternalActivity
                    SendCoinsInternalActivity.start(SendCoinsQrActivity.this, getIntent().getAction(), paymentIntent, false, quickScan);

                    if (quickScan) {
                        SendCoinsQrActivity.this.finish();
                    }
                }

                @Override
                protected void handlePrivateKey(final PrefixedChecksummedBytes key) {
                    SweepWalletActivity.start(SendCoinsQrActivity.this, key, false);

                    if (isQuickScan()) {
                        SendCoinsQrActivity.this.finish();
                    }
                }

                @Override
                protected void handleDirectTransaction(final Transaction transaction) throws VerificationException {
                    final WalletApplication application = (WalletApplication) getApplication();
                    application.processDirectTransaction(transaction);

                    if (isQuickScan()) {
                        SendCoinsQrActivity.this.finish();
                    }
                }

                @Override
                protected void error(Exception x, final int messageResId, final Object... messageArgs) {
                    BaseAlertDialogBuilder alertDialogBuilder = new BaseAlertDialogBuilder(SendCoinsQrActivity.this);
                    alertDialogBuilder.setMessage(formatString(SendCoinsQrActivity.this, messageResId, messageArgs));
                    alertDialogBuilder.setNeutralText(getString(R.string.button_dismiss));
                    alertDialogBuilder.setNeutralAction(
                            () -> {
                                if (isQuickScan()) {
                                    SendCoinsQrActivity.this.finish();
                                }
                                return Unit.INSTANCE;
                            }
                    );
                }
            }.parse();
        } else {
            if (isQuickScan()) {
                finish();
            }
        }
    }
}
