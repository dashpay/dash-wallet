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
import android.content.DialogInterface;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import org.dash.wallet.common.ui.DialogBuilder;

import de.schildbach.wallet.ui.BackupWalletToSeedDialogFragment;
import de.schildbach.wallet_test.R;

public class UpgradeWalletDisclaimerDialog extends DialogFragment {

    private static final String FRAGMENT_TAG = UpgradeWalletDisclaimerDialog .class.getName();

    public static void show(FragmentManager fm) {
        UpgradeWalletDisclaimerDialog dialogFragment = new UpgradeWalletDisclaimerDialog();
        dialogFragment.show(fm, FRAGMENT_TAG);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState) {
        setCancelable(false);
        return super.onCreateView(inflater, container, savedInstanceState);
    }

    @Override
    public Dialog onCreateDialog(final Bundle savedInstanceState) {
        final DialogBuilder dialogBuilder = new DialogBuilder(getActivity());
        dialogBuilder.setTitle(R.string.encrypt_new_key_chain_dialog_title);

        String message = getString(R.string.encrypt_new_key_chain_dialog_message) + "\n\n" +
                getString(R.string.pin_code_required_dialog_message);
        dialogBuilder.setMessage(message);
        dialogBuilder.setCancelable(false);
        dialogBuilder.setPositiveButton(android.R.string.ok, null);

        String buttonText = getString(R.string.export_keys_dialog_title_to_seed) + " / " + getString(R.string.wallet_options_encrypt_keys_set);
        dialogBuilder.setPositiveButton(buttonText,
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(final DialogInterface dialog, final int which) {
                        BackupWalletToSeedDialogFragment.show(getFragmentManager(), true);
                    }
                });

        return dialogBuilder.create();
    }

    public interface OnUpgradeConfirmedListener {
        void onUpgradeConfirmed();
    }

}
