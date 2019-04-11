package de.schildbach.wallet.ui;

import android.content.DialogInterface;
import android.os.Bundle;

import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet_test.R;

/**
 * @author Samuel Barbosa
 */
public class ContactsActivity extends AbstractBindServiceActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.contacts_activity);

        UnlockWalletDialogFragment.show(getSupportFragmentManager(), new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                if (!WalletApplication.getInstance().getWallet().isEncrypted()) {
                    CreateBlockchainUserDialog createBlockchainUserDialog = new CreateBlockchainUserDialog();
                    createBlockchainUserDialog.show(getSupportFragmentManager(), "");
                }
            }
        }, true);
    }

}
