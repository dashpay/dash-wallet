package de.schildbach.wallet.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.livedata.EncryptWalletLiveData
import org.slf4j.LoggerFactory

class SetPinViewModel(application: Application) : AndroidViewModel(application) {

    private val log = LoggerFactory.getLogger(SetPinViewModel::class.java)

    private val walletApplication = application as WalletApplication

    val pin = arrayListOf<Int>()

    internal val startActivityAction = SingleLiveEvent<Pair<Class<*>, Boolean>>()
    internal val encryptWalletLiveData = EncryptWalletLiveData(application)

    fun setPin(pin: ArrayList<Int>) {
        this.pin.clear()
        this.pin.addAll(pin)
    }

    fun encryptKeys() {
        val password = pin.joinToString("")
        if (!walletApplication.wallet.isEncrypted) {
            encryptWalletLiveData.encrypt(password, walletApplication.scryptIterationsTarget())
        } else {
            log.warn("Trying to encrypt already encrypted wallet")
        }
    }

    fun checkPin() {
        val password = pin.joinToString("")
        checkPin(password)
    }

    fun checkPin(password: String) {
        if (walletApplication.wallet.isEncrypted) {
            encryptWalletLiveData.checkPin(password)
        } else {
            log.warn("Trying to decrypt unencrypted wallet")
        }
    }

    fun initWallet() {
        startActivityAction.call(Pair(VerifySeedActivity::class.java, true))
        //startActivityAction.call(Pair(WalletActivity::class.java, true))
    }
}
