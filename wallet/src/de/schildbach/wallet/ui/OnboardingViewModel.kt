package de.schildbach.wallet.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.util.WalletUtils
import de.schildbach.wallet_test.R
import org.bitcoinj.crypto.MnemonicCode
import org.bitcoinj.crypto.MnemonicException
import org.bitcoinj.wallet.Wallet
import org.slf4j.LoggerFactory

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val log = LoggerFactory.getLogger(OnboardingViewModel::class.java)

    private val walletApplication = application as WalletApplication

    private var basicWalletStuffInitialised = false

    private val _showMessageAction = SingleLiveEvent<String>()
    val showToastAction
        get() = _showMessageAction

    private val _showRestoreWalletFailureAction = SingleLiveEvent<MnemonicException>()
    val showRestoreWalletFailureAction
        get() = _showRestoreWalletFailureAction

    private val _startActivityAction = SingleLiveEvent<Intent>()
    val startActivityAction
        get() = _startActivityAction

//    private val _initWalletAsyncLiveData = InitWalletAsyncLiveData(application)
//    val initWalletAsyncLiveData: InitWalletAsyncLiveData
//        get() = _initWalletAsyncLiveData

    fun initBasicWalletStuffIfNeeded() {
        if (!basicWalletStuffInitialised) {
            walletApplication.initEnvironment()
            basicWalletStuffInitialised = false
        }
    }

    fun createNewWallet() {
        initBasicWalletStuffIfNeeded()
        val wallet = Wallet(Constants.NETWORK_PARAMETERS)
        walletApplication.wallet = wallet
        _startActivityAction.call(SetPinActivity.createIntent(getApplication(), R.string.set_pin_create_new_wallet))
    }

    fun restoreWalletFromSeed(words: MutableList<String>) {
        try {
            MnemonicCode.INSTANCE.check(words)
        } catch (x: MnemonicException) {
            log.info("problem restoring wallet from seed: ", x)
            _showRestoreWalletFailureAction.call(x)
            return
        }
        val wallet = WalletUtils.restoreWalletFromSeed(words, Constants.NETWORK_PARAMETERS)
        walletApplication.wallet = wallet
        log.info("successfully restored wallet from seed")
        _startActivityAction.call(SetPinActivity.createIntent(getApplication(), R.string.set_pin_restore_wallet))
    }

    fun restoreWalletFromFile(wallet: Wallet) {
        walletApplication.wallet = wallet
        log.info("successfully restored wallet from file")
        _startActivityAction.call(SetPinActivity.createIntent(getApplication(), R.string.set_pin_restore_wallet))
    }
}
