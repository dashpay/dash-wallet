package de.schildbach.wallet.ui;

import android.os.Bundle;

import org.spongycastle.crypto.params.KeyParameter;

import de.schildbach.wallet_test.R;

/**
 * @author Samuel Barbosa
 */
public class ContactsActivity extends AbstractBindServiceActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.contacts_activity);

        UnlockWalletDialogFragment.show(getSupportFragmentManager(), null,
                new UnlockWalletDialogFragment.OnWalletUnlockedListener() {
                    @Override
                    public void onWalletUnlocked(KeyParameter encryptionKey) {
                        CreateBlockchainUserDialog.show(getSupportFragmentManager(), encryptionKey);
                    }
                });
    }

}
