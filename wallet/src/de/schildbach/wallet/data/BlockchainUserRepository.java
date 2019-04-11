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
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;

import de.schildbach.wallet.WalletApplication;

/**
 * @author Samuel Barbosa
 */
public class BlockchainUserRepository {

    public static final ImmutableList<ChildNumber> EVOLUTION_ACCOUNT_PATH = ImmutableList.of(new ChildNumber(5, true),
            ChildNumber.FIVE_HARDENED, ChildNumber.ZERO_HARDENED);

    public LiveData<Transaction> createBlockchainUser(String username) throws InsufficientMoneyException {
        Wallet wallet = WalletApplication.getInstance().getWallet();
        final MutableLiveData<Transaction> liveData = new MutableLiveData<>();

        wallet.addKeyChain(EVOLUTION_ACCOUNT_PATH);
        ECKey privKey = ECKey.fromPrivate(wallet.getActiveKeyChain().getKeyByPath(EVOLUTION_ACCOUNT_PATH,
                false).getPrivKeyBytes());
        SubTxRegister subTxRegister = new SubTxRegister(1, username, privKey);
        SendRequest request = SendRequest.forSubTxRegister(wallet.getParams(),
                subTxRegister, Coin.MILLICOIN);

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
