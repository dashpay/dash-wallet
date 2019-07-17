package de.schildbach.wallet.livedata

import android.annotation.SuppressLint
import android.app.Application
import android.os.AsyncTask
import androidx.lifecycle.MutableLiveData
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import org.slf4j.LoggerFactory

class InitWalletAsyncLiveData(application: Application) : MutableLiveData<Resource<Void?>>() {

    private val log = LoggerFactory.getLogger(InitWalletAsyncLiveData::class.java)

    private var initWalletTask: InitWalletTask? = null

    private var walletApplication = application as WalletApplication

    fun init() {
        if (initWalletTask != null) {
            initWalletTask!!.cancel(false)
        } else {
            initWalletTask = InitWalletTask()
            asdf()
        }
    }

    private fun asdf() {
        value = Resource.loading(null)

        try {
            walletApplication.fullInitialization()
            value = Resource.success(null)
        } catch (x: Exception) {
            value = Resource.error(x.message!!, null)
        }
    }

    @SuppressLint("StaticFieldLeak")
    internal inner class InitWalletTask : AsyncTask<Void, Void, Resource<Void?>>() {

        override fun onPreExecute() {
            value = Resource.loading(null)
        }

        override fun doInBackground(vararg args: Void): Resource<Void?> {
            try {
                org.bitcoinj.core.Context.propagate(Constants.CONTEXT)
                walletApplication.fullInitialization()
            } catch (x: Exception) {
                return Resource.error(x.message!!, null)
            }
            return Resource.success(null)
        }

        override fun onPostExecute(result: Resource<Void?>) {
            value = result
        }
    }
}