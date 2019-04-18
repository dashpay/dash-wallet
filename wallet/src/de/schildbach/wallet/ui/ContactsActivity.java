package de.schildbach.wallet.ui;

import android.arch.lifecycle.ViewModelProviders;
import android.os.Bundle;

import de.schildbach.wallet_test.R;

/**
 * @author Samuel Barbosa
 */
public class ContactsActivity extends AbstractBindServiceActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.contacts_activity);

        BlockchainUserViewModel viewModel = ViewModelProviders.of(this)
                .get(BlockchainUserViewModel.class);

        viewModel.getUser().observe(this, blockchainUser -> {
            if (blockchainUser == null) {
                UnlockWalletDialogFragment.show(getSupportFragmentManager(), null,
                    encryptionKey -> CreateBlockchainUserDialog.show(getSupportFragmentManager(), encryptionKey));
            } else {
                setTitle("Hello " + blockchainUser.getUname());
            }
        });
    }

}
