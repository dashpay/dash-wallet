package de.schildbach.wallet.livedata

import android.annotation.SuppressLint
import android.app.Application
import android.os.AsyncTask
import androidx.lifecycle.MutableLiveData
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import org.bitcoinj.crypto.KeyCrypterException
import org.bitcoinj.crypto.KeyCrypterScrypt
import org.bitcoinj.wallet.Wallet
import org.slf4j.LoggerFactory

class EncryptWalletLiveData(application: Application) : MutableLiveData<Resource<Wallet>>() {

    private val log = LoggerFactory.getLogger(EncryptWalletLiveData::class.java)

    private var encryptKeysTask: EncryptKeysTask? = null
    private var scryptIterationsTarget: Int = Constants.SCRYPT_ITERATIONS_TARGET
    private var wallet: Wallet? = null
    private var walletApplication = application as WalletApplication

    fun encrypt(wallet: Wallet, password: String, scryptIterationsTarget: Int) {
        this.scryptIterationsTarget = scryptIterationsTarget
        this.wallet = wallet
        if (encryptKeysTask != null) {
            encryptKeysTask!!.cancel(false)
        } else {
            encryptKeysTask = EncryptKeysTask()
            encryptKeysTask!!.execute(password)
        }
    }

    @SuppressLint("StaticFieldLeak")
    internal inner class EncryptKeysTask : AsyncTask<String, Void, Resource<Wallet>>() {

        override fun onPreExecute() {
            value = Resource.loading(null)
        }

        override fun doInBackground(vararg args: String): Resource<Wallet> {
            val password = args[0]
            try {
                // For the new key, we create a new key crypter according to the desired parameters.
                val keyCrypter = KeyCrypterScrypt(scryptIterationsTarget)
                val newKey = keyCrypter.deriveKey(password)
                wallet!!.encrypt(keyCrypter, newKey)

                org.bitcoinj.core.Context.propagate(Constants.CONTEXT)
                walletApplication.saveWalletAndFinalizeInitialization()
                walletApplication.configuration.setOnboardingComplete()

                log.info("wallet successfully encrypted, using key derived by new spending password (${keyCrypter.scryptParameters.n} scrypt iterations)")

                return Resource.success(wallet)
            } catch (x: KeyCrypterException) {
                return Resource.error(x.message!!, null)
            }
        }

        override fun onPostExecute(result: Resource<Wallet>) {
            value = result
        }

        override fun onCancelled(result: Resource<Wallet>?) {
            super.onCancelled(result)
        }
    }
}