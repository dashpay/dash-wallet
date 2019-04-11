package de.schildbach.wallet.ui;

import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.ViewModel;

import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.Transaction;

import de.schildbach.wallet.data.BlockchainUserRepository;

/**
 * @author Samuel Barbosa
 */
public class BlockchainUserViewModel extends ViewModel {

    BlockchainUserRepository repository = new BlockchainUserRepository();

    public LiveData<Transaction> createBlockchainUser(String username, byte[] encryptionKey) throws InsufficientMoneyException {
        return repository.createBlockchainUser(username, encryptionKey);
    }

}
