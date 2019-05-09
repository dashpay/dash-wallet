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

import android.app.ProgressDialog;
import android.arch.lifecycle.ViewModelProviders;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Toast;

import org.dash.wallet.common.util.ProgressDialogUtils;

import java.util.Arrays;

import de.schildbach.wallet.data.BlockchainUser;
import de.schildbach.wallet.data.LoadingType;
import de.schildbach.wallet.data.StatusType;
import de.schildbach.wallet.data.SuccessType;
import de.schildbach.wallet_test.R;

/**
 * @author Samuel Barbosa
 */
public class ContactsActivity extends AbstractBindServiceActivity {

    BlockchainUserViewModel viewModel;
    private ProgressDialog loadingDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.contacts_activity);

        showLoading();
        viewModel = ViewModelProviders.of(this).get(BlockchainUserViewModel.class);
        viewModel.getUser().observe(this, buResource -> {
            StatusType status = buResource.status;
            if (Arrays.asList(SuccessType.values()).contains(status)) {
                hideLoading();
                if (buResource.data != null) {
                    userLoaded(buResource.data);
                } else {
                    showCreateUserDialog();
                }
            } else if (Arrays.asList(LoadingType.values()).contains(status)) {
                showLoading();
            } else {
                hideLoading();
                Toast.makeText(this, R.string.unknown_error, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        hideLoading();
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
        case android.R.id.home:
            finish();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void showCreateUserDialog() {
        UnlockWalletDialogFragment.show(getSupportFragmentManager(), null,
                encryptionKey -> CreateBlockchainUserDialog.show(getSupportFragmentManager(), encryptionKey));
    }

    private void userLoaded(BlockchainUser user) {
        String title = getString(R.string.hello_user, user.getUname());
        setTitle(title);
    }

    private void showLoading() {
        if (loadingDialog == null) {
            loadingDialog = ProgressDialogUtils.createSpinningLoading(this);
        }
        loadingDialog.show();
    }

    private void hideLoading() {
        if (loadingDialog != null) {
            loadingDialog.cancel();
        }
    }
}
