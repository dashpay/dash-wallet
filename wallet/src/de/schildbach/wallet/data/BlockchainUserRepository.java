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

package de.schildbach.wallet.data;

import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.MutableLiveData;

import com.google.common.collect.ImmutableList;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.KeyCrypterScrypt;
import org.bitcoinj.evolution.SubTxRegister;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.DeterministicKeyChain;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;
import org.spongycastle.crypto.params.KeyParameter;

import java.util.ArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import de.schildbach.wallet.AppDatabase;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;

/**
 * @author Samuel Barbosa
 */
public class BlockchainUserRepository {

    private Executor executor;
    private static BlockchainUserRepository instance;

    private BlockchainUserRepository() {
        executor = Executors.newSingleThreadExecutor();
    }

    public static BlockchainUserRepository getInstance() {
        if (instance == null) {
            instance = new BlockchainUserRepository();
        }
        return instance;
    }

    public static final ImmutableList<ChildNumber> EVOLUTION_ACCOUNT_PATH = ImmutableList.of(new ChildNumber(5, true),
            ChildNumber.FIVE_HARDENED, ChildNumber.ZERO_HARDENED);

    public LiveData<Transaction> createBlockchainUser(final String username, byte[] encryptionKeyBytes) throws InsufficientMoneyException {
        WalletApplication application = WalletApplication.getInstance();
        Wallet wallet = application.getWallet();
        final MutableLiveData<Transaction> liveData = new MutableLiveData<>();
        executor.execute(() -> {
            KeyParameter encryptionKey = new KeyParameter(encryptionKeyBytes);
            Context.propagate(Constants.CONTEXT);

            if (!wallet.hasKeyChain(EVOLUTION_ACCOUNT_PATH)) {
                if (wallet.isEncrypted()) {
                    wallet.decrypt(encryptionKey);
                }
                wallet.addKeyChain(EVOLUTION_ACCOUNT_PATH);
                if (!wallet.isEncrypted()) {
                    final KeyCrypterScrypt keyCrypter = new KeyCrypterScrypt(application.scryptIterationsTarget());
                    wallet.encrypt(keyCrypter, encryptionKey);
                }
            }

            DeterministicKeyChain keyChain = wallet.getActiveKeyChain().toDecrypted(encryptionKey);
            ECKey privKey = ECKey.fromPrivate(keyChain.getKeyByPath(EVOLUTION_ACCOUNT_PATH,
                    false).getPrivKeyBytes());
            SubTxRegister subTxRegister = new SubTxRegister(1, username, privKey);
            SendRequest request = SendRequest.forSubTxRegister(wallet.getParams(),
                    subTxRegister, Coin.MILLICOIN);

            request.aesKey = encryptionKey;
            final Wallet.SendResult result;

            try {
                result = wallet.sendCoins(request);
                result.broadcastComplete.addListener(() -> liveData.postValue(result.tx), Threading.USER_THREAD);
                result.broadcastComplete.addListener(() -> storeBlockchainUser(result.tx, username), Threading.THREAD_POOL);
            } catch (InsufficientMoneyException e) {
                //TODO: Handle error
                e.printStackTrace();
            }
        });

        return liveData;
    }

    private void storeBlockchainUser(Transaction regTx, String username) {
        ArrayList<String> subTxList = new ArrayList<>(1);
        String regTxId = regTx.getHashAsString();
        subTxList.add(regTxId);
        BlockchainUser blockchainUser = new BlockchainUser(regTxId, username, "",
                regTx.getInputSum(), "", "open", subTxList);
        AppDatabase.getAppDatabase().blockchainUserDao().insert(blockchainUser);
    }

    public LiveData<BlockchainUser> getUser() {
        return AppDatabase.getAppDatabase().blockchainUserDao().get();
    }

}
