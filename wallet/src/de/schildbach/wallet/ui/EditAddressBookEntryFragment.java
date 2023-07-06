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
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import org.bitcoinj.core.Address;
import org.bitcoinj.wallet.Wallet;
import org.dash.wallet.common.ui.BaseDialogFragment;

import javax.annotation.Nullable;

import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.data.AddressBookProvider;
import de.schildbach.wallet.util.WalletUtils;
import de.schildbach.wallet_test.R;
import kotlin.Unit;

/**
 * @author Andreas Schildbach
 */
public final class EditAddressBookEntryFragment extends BaseDialogFragment {
    private static final String FRAGMENT_TAG = EditAddressBookEntryFragment.class.getName();

    private static final String KEY_ADDRESS = "address";
    private static final String KEY_SUGGESTED_ADDRESS_LABEL = "suggested_address_label";

    public static void edit(final FragmentManager fm, final String address) {
        edit(fm, Address.fromString(Constants.NETWORK_PARAMETERS, address), null);
    }

    public static void edit(final FragmentManager fm, final Address address) {
        edit(fm, address, null);
    }

    public static void edit(final FragmentManager fm, final Address address,
            @Nullable final String suggestedAddressLabel) {
        final DialogFragment newFragment = EditAddressBookEntryFragment.instance(address, suggestedAddressLabel);
        newFragment.show(fm, FRAGMENT_TAG);
    }

    private static EditAddressBookEntryFragment instance(final Address address,
            @Nullable final String suggestedAddressLabel) {
        final EditAddressBookEntryFragment fragment = new EditAddressBookEntryFragment();

        final Bundle args = new Bundle();
        args.putString(KEY_ADDRESS, address.toString());
        args.putString(KEY_SUGGESTED_ADDRESS_LABEL, suggestedAddressLabel);
        fragment.setArguments(args);

        return fragment;
    }

    private Activity activity;
    private Wallet wallet;
    private ContentResolver contentResolver;

    @Override
    public void onAttach(final Activity activity) {
        super.onAttach(activity);

        this.activity = activity;
        final WalletApplication application = (WalletApplication) activity.getApplication();
        this.wallet = application.getWallet();
        this.contentResolver = activity.getContentResolver();
    }

    @Override
    public Dialog onCreateDialog(final Bundle savedInstanceState) {
        final Bundle args = getArguments();
        final Address address = Address.fromString(Constants.NETWORK_PARAMETERS, args.getString(KEY_ADDRESS));
        final String suggestedAddressLabel = args.getString(KEY_SUGGESTED_ADDRESS_LABEL);

        final LayoutInflater inflater = LayoutInflater.from(activity);

        final Uri uri = AddressBookProvider.contentUri(activity.getPackageName()).buildUpon()
                .appendPath(address.toString()).build();

        final String label = AddressBookProvider.resolveLabel(activity, address.toString());

        final boolean isAdd = label == null;
        final boolean isOwn = wallet.isPubKeyHashMine(address.getHash160());

        final View view = inflater.inflate(R.layout.edit_address_book_entry_dialog, null);
        final TextView viewAddress = (TextView) view.findViewById(R.id.edit_address_book_entry_address);
        viewAddress.setText(WalletUtils.formatAddress(address, Constants.ADDRESS_FORMAT_GROUP_SIZE,
                Constants.ADDRESS_FORMAT_LINE_SIZE));

        final TextView viewLabel = (TextView) view.findViewById(R.id.edit_address_book_entry_label);
        viewLabel.setText(label != null ? label : suggestedAddressLabel);

        if (isOwn){
            baseAlertDialogBuilder.setTitle(isAdd ? getString(R.string.edit_address_book_entry_dialog_title_add_receive):getString(R.string.edit_address_book_entry_dialog_title_edit_receive));
        } else {
            baseAlertDialogBuilder.setTitle(isAdd ? getString(R.string.edit_address_book_entry_dialog_title_add):getString(R.string.edit_address_book_entry_dialog_title_edit));
        }
        baseAlertDialogBuilder.setView(view);
        baseAlertDialogBuilder.setPositiveText(isAdd ? getString(R.string.button_add):getString(R.string.edit_address_book_entry_dialog_button_edit));
        baseAlertDialogBuilder.setPositiveAction(
                () -> {
                    final String newLabel = viewLabel.getText().toString().trim();
                    if (!newLabel.isEmpty()) {
                        final ContentValues values = new ContentValues();
                        values.put(AddressBookProvider.KEY_LABEL, newLabel);

                        if (isAdd)
                            contentResolver.insert(uri, values);
                        else
                            contentResolver.update(uri, values, null, null);
                    } else if (!isAdd) {
                        contentResolver.delete(uri, null, null);
                    }
                    return Unit.INSTANCE;
                }
        );

        if (!isAdd){
            baseAlertDialogBuilder.setNeutralText(getString(R.string.button_delete));
            baseAlertDialogBuilder.setNeutralAction(
                    () -> {
                        contentResolver.delete(uri, null, null);
                        return Unit.INSTANCE;
                    }
            );
        }
        baseAlertDialogBuilder.setNegativeText(getString(R.string.button_cancel));
        alertDialog = baseAlertDialogBuilder.buildAlertDialog();
        alertDialog.setOnShowListener(d -> {
            Button negativeButton = alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            Button neutralButton = alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL);

            int colorMediumGray = getResources().getColor(R.color.content_secondary, null);
            if (negativeButton != null) {
                negativeButton.setTextColor(colorMediumGray);
            }
            if (neutralButton != null) {
                neutralButton.setTextColor(colorMediumGray);
            }
        });

        return super.onCreateDialog(savedInstanceState);
    }
}
