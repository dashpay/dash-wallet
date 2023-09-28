package de.schildbach.wallet.ui.backup;

import org.bitcoinj.wallet.Wallet;

public interface OnRestoreWalletListener {
    void onRestoreWallet(Wallet wallet);
    void onRetryRequest();
}
