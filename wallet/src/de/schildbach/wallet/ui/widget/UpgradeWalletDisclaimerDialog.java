/*
 * Copyright 2014-2015 the original author or authors.
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

package de.schildbach.wallet.ui.widget;

import android.app.Dialog;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import org.dash.wallet.common.ui.BaseDialogFragment;

import de.schildbach.wallet.ui.BackupWalletToSeedDialogFragment;
import de.schildbach.wallet_test.R;
import kotlin.Unit;
public class UpgradeWalletDisclaimerDialog extends BaseDialogFragment {

    private static final String FRAGMENT_TAG = UpgradeWalletDisclaimerDialog .class.getName();
    private static final String ARGS_DISMISS_ON_APP_LOCK = "dismiss_on_app_lock";

    public static void show(FragmentManager fm, boolean dismissOnAppLock) {
        UpgradeWalletDisclaimerDialog dialogFragment = new UpgradeWalletDisclaimerDialog();
        Bundle bundle = new Bundle();
        bundle.putBoolean(ARGS_DISMISS_ON_APP_LOCK, dismissOnAppLock);
        dialogFragment.setArguments(bundle);
        dialogFragment.show(fm, FRAGMENT_TAG);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState) {
        setCancelable(false);
        return super.onCreateView(inflater, container, savedInstanceState);
    }

    @Override
    public Dialog onCreateDialog(final Bundle savedInstanceState) {
        String message = getString(R.string.encrypt_new_key_chain_dialog_message) + "\n\n" +
                getString(R.string.pin_code_required_dialog_message);
        String buttonText = getString(R.string.export_keys_dialog_title_to_seed) + " / " + getString(R.string.wallet_options_encrypt_keys_set);
        baseAlertDialogBuilder.setTitle(getString(R.string.encrypt_new_key_chain_dialog_title));
        baseAlertDialogBuilder.setMessage(message);
        baseAlertDialogBuilder.setCancelable(false);
        baseAlertDialogBuilder.setPositiveText(buttonText);
        baseAlertDialogBuilder.setPositiveAction(
                () -> {
                    if (isAdded()){
                        BackupWalletToSeedDialogFragment.show(getParentFragmentManager(), true);
                    }
                    return Unit.INSTANCE;
                }
        );

        alertDialog = baseAlertDialogBuilder.buildAlertDialog();
        return super.onCreateDialog(savedInstanceState);
    }

    public interface OnUpgradeConfirmedListener {
        void onUpgradeConfirmed();
    }

}
