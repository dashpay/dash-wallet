/*
 * Copyright © 2019 Dash Core Group. All rights reserved.
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

import android.arch.lifecycle.ViewModelProviders;
import android.os.Bundle;
import android.widget.Toast;

import de.schildbach.wallet.data.BlockchainUser;
import de.schildbach.wallet_test.R;

/**
 * @author Samuel Barbosa
 */
public class ContactsActivity extends AbstractBindServiceActivity {

    BlockchainUserViewModel viewModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.contacts_activity);

        showLoading();
        viewModel = ViewModelProviders.of(this).get(BlockchainUserViewModel.class);
        viewModel.getUser().observe(this, buResource -> {
            switch (buResource.status) {
                case SUCCESS:
                    hideLoading();
                    if (buResource.data != null) {
                        userLoaded(buResource.data);
                    } else {
                        showCreateUserDialog();
                    }
                    break;
                case LOADING:
                    showLoading();
                    break;
                case ERROR:
                    hideLoading();
                    Toast.makeText(this, buResource.message, Toast.LENGTH_SHORT).show();
                    break;
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        hideLoading();
    }

    private void showCreateUserDialog() {
        UnlockWalletDialogFragment.show(getSupportFragmentManager(), null,
                encryptionKey -> CreateBlockchainUserDialog.show(getSupportFragmentManager(), encryptionKey));
    }

    private void userLoaded(BlockchainUser user) {
        setTitle("Hello " + user.getUname());
    }

    private void showLoading() {
        ProgressDialogFragment.showProgress(getSupportFragmentManager(), getString(R.string.loading));
    }

    private void hideLoading() {
        //TODO: Use another loading.
        try {
            ProgressDialogFragment.dismissProgress(getSupportFragmentManager());
        } catch (NullPointerException e) {
            //It was not being shown after all
        }
    }
}
