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

package de.schildbach.wallet.ui.backup;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.net.Uri;

import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewModelStoreOwner;

import com.google.common.base.Charsets;

import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.WalletExtension;
import org.dash.wallet.common.Configuration;
import org.dash.wallet.common.ui.BaseAlertDialogBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import javax.annotation.Nullable;

import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.util.Crypto;
import de.schildbach.wallet.util.Io;
import de.schildbach.wallet.util.WalletUtils;
import de.schildbach.wallet_test.R;
import kotlin.Unit;

public class RestoreFromFileHelper {

    protected static final Logger log = LoggerFactory.getLogger(RestoreFromFileHelper.class);


    public static Dialog createRestoreWalletPermissionDialog(Activity context, ViewModelStoreOwner viewModelStoreOwner, LifecycleOwner lifecycleOwner) {
        final BaseAlertDialogBuilder restoreWalletPermAlertDialogBuilder = new BaseAlertDialogBuilder(context);
        restoreWalletPermAlertDialogBuilder.setTitle(context.getString(R.string.restore_wallet_permission_dialog_title));
        restoreWalletPermAlertDialogBuilder.setMessage(context.getString(R.string.restore_wallet_permission_dialog_message));
        restoreWalletPermAlertDialogBuilder.setNeutralText(context.getString(R.string.button_dismiss));
        return restoreWalletPermAlertDialogBuilder.buildAlertDialog();
    }

    @SuppressLint("StringFormatInvalid")
    public static void restoreWalletFromProtobuf(final Activity activity,
                                                 ViewModelStoreOwner viewModelStoreOwner, LifecycleOwner lifecycleOwner,
                                                 final Uri walletUri, final InputStream is, @Nullable final WalletExtension[] walletExtensions, final OnRestoreWalletListener listener) {
        try {
            listener.onRestoreWallet(WalletUtils.restoreWalletFromProtobuf(is, Constants.NETWORK_PARAMETERS, walletExtensions));

            log.info("successfully restored unencrypted wallet: {}", walletUri);
        } catch (final IOException x) {
            final BaseAlertDialogBuilder restoreWalletFromProtobufAlertDialogBuilder = new BaseAlertDialogBuilder(activity);
            restoreWalletFromProtobufAlertDialogBuilder.setTitle(activity.getString(R.string.import_export_keys_dialog_failure_title));
            restoreWalletFromProtobufAlertDialogBuilder.setMessage(activity.getString(R.string.import_keys_dialog_failure, x.getMessage()));
            restoreWalletFromProtobufAlertDialogBuilder.setPositiveText(activity.getString(R.string.button_dismiss));
            restoreWalletFromProtobufAlertDialogBuilder.setNegativeText(activity.getString(R.string.button_retry));
            restoreWalletFromProtobufAlertDialogBuilder.setNegativeAction(
                    () -> {
                        listener.onRetryRequest();
                        return Unit.INSTANCE;
                    }
            );
            restoreWalletFromProtobufAlertDialogBuilder.setShowIcon(true);
            restoreWalletFromProtobufAlertDialogBuilder.buildAlertDialog().show();
            log.info("problem restoring unencrypted wallet: " + walletUri, x);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (final IOException x2) {
                    // swallow
                }
            }
        }
    }

    public static void restorePrivateKeysFromBase58(final Activity activity,
                                                    ViewModelStoreOwner viewModelStoreOwner, LifecycleOwner lifecycleOwner,
                                                    final Uri walletUri, final InputStream is, final OnRestoreWalletListener listener) {
        try {
            listener.onRestoreWallet(WalletUtils.restorePrivateKeysFromBase58(is, Constants.NETWORK_PARAMETERS));
            //remind user to backup since the key backup file does not have an HD seed
            //Each time the user restores this backup file a new HD seed will be generated
            Configuration config = ((WalletApplication) activity.getApplication()).getConfiguration();
            config.armBackupReminder();
            config.armBackupSeedReminder();
            log.info("successfully restored unencrypted private keys: {}", walletUri);
        } catch (final IOException x) {
            final BaseAlertDialogBuilder restorePrivateKeyFailedAlertDialogBuilder = new BaseAlertDialogBuilder(activity);
            restorePrivateKeyFailedAlertDialogBuilder.setTitle(activity.getString(R.string.import_export_keys_dialog_failure_title));
            restorePrivateKeyFailedAlertDialogBuilder.setMessage(activity.getString(R.string.import_keys_dialog_failure, x.getMessage()));
            restorePrivateKeyFailedAlertDialogBuilder.setPositiveText(activity.getString(R.string.button_dismiss));
            restorePrivateKeyFailedAlertDialogBuilder.setNegativeText(activity.getString(R.string.button_retry));
            restorePrivateKeyFailedAlertDialogBuilder.setNegativeAction(
                    () -> {
                        listener.onRetryRequest();
                        return Unit.INSTANCE;
                    }
            );
            restorePrivateKeyFailedAlertDialogBuilder.setShowIcon(true);
            restorePrivateKeyFailedAlertDialogBuilder.buildAlertDialog().show();

            log.info("problem restoring private keys: " + walletUri, x);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (final IOException x2) {
                    // swallow
                }
            }
        }
    }

    public static void restoreWalletFromEncrypted(final Activity activity,
                                                  final ViewModelStoreOwner viewModelStoreOwner,
                                                  final LifecycleOwner lifecycleOwner,
                                                  final Uri walletUri, final InputStream isFile,
                                                   final String password, final OnRestoreWalletListener listener) {
        try {
            final BufferedReader cipherIn = new BufferedReader(
                    new InputStreamReader(isFile, Charsets.UTF_8));
            final StringBuilder cipherText = new StringBuilder();
            Io.copy(cipherIn, cipherText, Constants.BACKUP_MAX_CHARS);
            cipherIn.close();

            final byte[] plainText = Crypto.decryptBytes(cipherText.toString(), password.toCharArray());
            final InputStream is = new ByteArrayInputStream(plainText);

            listener.onRestoreWallet(WalletUtils.restoreWalletFromProtobufOrBase58(is, Constants.NETWORK_PARAMETERS));

            log.info("successfully restored encrypted wallet: {}", walletUri);
        } catch (final IOException x) {

            final BaseAlertDialogBuilder restoreWalletFromEncryptAlertDialogBuilder = new BaseAlertDialogBuilder(activity);
            restoreWalletFromEncryptAlertDialogBuilder.setTitle(activity.getString(R.string.import_export_keys_dialog_failure_title));
            restoreWalletFromEncryptAlertDialogBuilder.setMessage(activity.getString(R.string.import_keys_dialog_failure, x.getMessage()));
            restoreWalletFromEncryptAlertDialogBuilder.setPositiveText(activity.getString(R.string.button_dismiss));
            restoreWalletFromEncryptAlertDialogBuilder.setNegativeText(activity.getString(R.string.button_retry));
            restoreWalletFromEncryptAlertDialogBuilder.setNegativeAction(
                    () -> {
                        listener.onRetryRequest();
                        return Unit.INSTANCE;
                    }
            );
            restoreWalletFromEncryptAlertDialogBuilder.setShowIcon(true);
            restoreWalletFromEncryptAlertDialogBuilder.buildAlertDialog().show();

            log.info("problem restoring wallet: " + walletUri, x);
        }
    }

    public interface OnRestoreWalletListener {

        void onRestoreWallet(Wallet wallet);

        void onRetryRequest();
    }
}
