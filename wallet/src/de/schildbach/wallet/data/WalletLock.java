package de.schildbach.wallet.data;

import org.bitcoinj.wallet.Wallet;

public class WalletLock {

    private boolean walletLocked = true;

    private static WalletLock instance;

    private WalletLock() {}

    public static WalletLock getInstance() {
        if (instance == null) {
            instance = new WalletLock();
        }
        return instance;
    }

    public boolean isWalletLocked(Wallet wallet) {
        return walletLocked && wallet.isEncrypted();
    }

    public void setWalletLocked(boolean walletLocked) {
        this.walletLocked = walletLocked;
    }

}
