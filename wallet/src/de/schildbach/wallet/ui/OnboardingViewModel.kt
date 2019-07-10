package de.schildbach.wallet.ui

import android.app.Application
import android.text.TextUtils
import androidx.lifecycle.AndroidViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.ui.send.SweepWalletFragment
import de.schildbach.wallet.util.WalletUtils
import org.bitcoinj.crypto.MnemonicCode
import org.bitcoinj.crypto.MnemonicException
import org.bitcoinj.wallet.Wallet
import org.slf4j.LoggerFactory

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val log = LoggerFactory.getLogger(SweepWalletFragment::class.java)

    private val walletApplication = application as WalletApplication

    private val _showMessageAction = SingleLiveEvent<String>()
    val showToastAction
        get() = _showMessageAction

    private val _showRestoreWalletFailureAction = SingleLiveEvent<MnemonicException>()
    val showRestoreWalletFailureAction
        get() = _showRestoreWalletFailureAction

    private val _launchWalletAction = SingleLiveEvent<Class<*>>()
    val launchWalletAction
        get() = _launchWalletAction

    fun createNewWallet() {
        walletApplication.initBaseStuff()
        val wallet = Wallet(Constants.NETWORK_PARAMETERS)
        walletApplication.initWithNewWallet(wallet)
        _launchWalletAction.call(WalletActivity::class.java)
    }

    fun restoreWalletFromSeed(words: MutableList<String>) {
        try {
            MnemonicCode.INSTANCE.check(words)
        } catch (x: MnemonicException) {
            log.info("problem restoring wallet from seed: ", x);
            _showRestoreWalletFailureAction.call(x)
            return
        }
        val wallet = WalletUtils.restoreWalletFromSeed(words, Constants.NETWORK_PARAMETERS)
        walletApplication.initWithNewWallet(wallet)
        log.info("successfully restored wallet from seed");
        _launchWalletAction.call(WalletActivity::class.java)
    }
}
