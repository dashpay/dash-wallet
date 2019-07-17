package de.schildbach.wallet.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.livedata.EncryptWalletLiveData
import org.bitcoinj.crypto.MnemonicException
import org.slf4j.LoggerFactory

class SetPinViewModel(application: Application) : AndroidViewModel(application) {

    private val log = LoggerFactory.getLogger(SetPinViewModel::class.java)

    private val walletApplication = application as WalletApplication

    val pin = arrayListOf<Int>()

    private val _showMessageAction = SingleLiveEvent<String>()
    val showToastAction
        get() = _showMessageAction

    private val _showRestoreWalletFailureAction = SingleLiveEvent<MnemonicException>()
    val showRestoreWalletFailureAction
        get() = _showRestoreWalletFailureAction

    private val _startActivityAction = SingleLiveEvent<Pair<Class<*>, Boolean>>()
    val startActivityAction
        get() = _startActivityAction

    private val _encryptKeysLiveData = EncryptWalletLiveData(application)
    val encryptWalletLiveData: EncryptWalletLiveData
        get() = _encryptKeysLiveData

    fun encryptKeys() {
        val password = pin.joinToString("")
        if (!walletApplication.wallet.isEncrypted) {
            _encryptKeysLiveData.encrypt(walletApplication.wallet, password, walletApplication.scryptIterationsTarget())
        } else {
            log.warn("Trying to encrypt already encrypted wallet")
        }
    }

    fun initWallet() {
//        walletApplication.saveWalletAndFinalizeInitialization()
        _startActivityAction.call(Pair(WalletActivity::class.java, false))
    }
}
