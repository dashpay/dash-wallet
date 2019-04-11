package de.schildbach.wallet.data;

import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.MutableLiveData;

import com.google.common.collect.ImmutableList;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.evolution.SubTxRegister;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.DeterministicKeyChain;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;
import org.spongycastle.crypto.params.KeyParameter;

import de.schildbach.wallet.WalletApplication;

/**
 * @author Samuel Barbosa
 */
public class BlockchainUserRepository {

    public static final ImmutableList<ChildNumber> EVOLUTION_ACCOUNT_PATH = ImmutableList.of(new ChildNumber(5, true),
            ChildNumber.FIVE_HARDENED, ChildNumber.ZERO_HARDENED);

    public LiveData<Transaction> createBlockchainUser(String username, byte[] encryptionKeyBytes) throws InsufficientMoneyException {
        Wallet wallet = WalletApplication.getInstance().getWallet();
        final MutableLiveData<Transaction> liveData = new MutableLiveData<>();

        if (!wallet.hasKeyChain(EVOLUTION_ACCOUNT_PATH)) {
            wallet.addKeyChain(EVOLUTION_ACCOUNT_PATH);
            WalletApplication.getInstance().saveWallet();
        }

        KeyParameter encryptionKey = new KeyParameter(encryptionKeyBytes);
        DeterministicKeyChain keyChain = wallet.getActiveKeyChain().toDecrypted(encryptionKey);
        ECKey privKey = ECKey.fromPrivate(keyChain.getKeyByPath(EVOLUTION_ACCOUNT_PATH,
                false).getPrivKeyBytes());
        SubTxRegister subTxRegister = new SubTxRegister(1, username, privKey);
        SendRequest request = SendRequest.forSubTxRegister(wallet.getParams(),
                subTxRegister, Coin.MILLICOIN);

        request.aesKey = encryptionKey;
        final Wallet.SendResult result = wallet.sendCoins(request);
        result.broadcastComplete.addListener(new Runnable() {
            @Override
            public void run() {
                liveData.postValue(result.tx);
            }
        }, Threading.USER_THREAD);

        return liveData;
    }

}
