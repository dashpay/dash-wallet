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

    internal val showToastAction = SingleLiveEvent<String>()
    internal val showRestoreWalletFailureAction = SingleLiveEvent<MnemonicException>()
    internal val startActivityAction = SingleLiveEvent<Intent>()

//    internal val initWalletAsyncLiveData = InitWalletAsyncLiveData(application)

    fun createNewWallet() {
        walletApplication.initEnvironmentIfNeeded()
        val wallet = Wallet(Constants.NETWORK_PARAMETERS)
        walletApplication.wallet = wallet
        walletApplication.configuration.armBackupSeedReminder()
        startActivityAction.call(SetPinActivity.createIntent(getApplication(), R.string.set_pin_create_new_wallet))
    }

    fun restoreWalletFromSeed(words: MutableList<String>) {
        try {
            MnemonicCode.INSTANCE.check(words)
        } catch (x: MnemonicException) {
            log.info("problem restoring wallet from seed: ", x)
            showRestoreWalletFailureAction.call(x)
            return
        }
        val wallet = WalletUtils.restoreWalletFromSeed(words, Constants.NETWORK_PARAMETERS)
        walletApplication.wallet = wallet
        log.info("successfully restored wallet from seed")
        walletApplication.configuration.disarmBackupSeedReminder()
        startActivityAction.call(SetPinActivity.createIntent(getApplication(), R.string.set_pin_restore_wallet))
    }
}
