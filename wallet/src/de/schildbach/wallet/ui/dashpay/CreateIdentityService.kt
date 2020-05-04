package de.schildbach.wallet.ui.dashpay

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.IdentityCreationState
import de.schildbach.wallet.ui.security.SecurityGuard
import de.schildbach.wallet.ui.send.DecryptSeedTask
import de.schildbach.wallet.ui.send.DeriveKeyTask
import de.schildbach.wallet_test.R
import kotlinx.coroutines.*
import org.bitcoinj.crypto.KeyCrypterException
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.Wallet
import org.bouncycastle.crypto.params.KeyParameter
import org.slf4j.LoggerFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine


class CreateIdentityService : Service() {

    companion object {
        private val log = LoggerFactory.getLogger(CreateIdentityService::class.java)

        private const val ACTION_CREATE_IDENTITY = "org.dash.dashpay.action.CREATE_IDENTITY"

        private const val EXTRA_USERNAME = "org.dash.dashpay.extra.USERNAME"

        @JvmStatic
        fun createIntent(context: Context, username: String): Intent {
            return Intent(context, CreateIdentityService::class.java).apply {
                action = ACTION_CREATE_IDENTITY
                putExtra(EXTRA_USERNAME, "username")
            }
        }
    }

    private val walletApplication by lazy { application as WalletApplication }
    private val platformRepo by lazy { PlatformRepo(walletApplication) }

    private lateinit var securityGuard: SecurityGuard

    private val notificationManager by lazy { getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main)

    lateinit var identityCreationState: IdentityCreationState

    override fun onCreate() {
        super.onCreate()
        try {
            securityGuard = SecurityGuard()
        } catch (e: Exception) {
            log.error("Unable to instantiate SecurityGuard", e)
            stopSelf()
            return
        }
        startForeground(
                Constants.NOTIFICATION_ID_DASHPAY_CREATE_IDENTITY,
                createNotification(R.string.processing_home_title, 0, -1, 0))
    }

    private fun updateNotification(@StringRes titleResId: Int, @StringRes messageResId: Int, progressMax: Int, progress: Int) {
        val notification: Notification = createNotification(titleResId, messageResId, progressMax, progress)
        notificationManager.notify(Constants.NOTIFICATION_ID_DASHPAY_CREATE_IDENTITY, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        if (intent != null) {

            when (intent.action) {
                ACTION_CREATE_IDENTITY -> handleCreateIdentityAction(intent)
            }

        }

        return START_NOT_STICKY
    }

    private fun handleCreateIdentityAction(intent: Intent) {
        val username = intent.getStringExtra(EXTRA_USERNAME)
        serviceScope.launch(Dispatchers.Main) {
            createIdentity(username)
            stopSelf()
        }
    }

    private suspend fun createIdentity(username: String) {

        val handler = Handler()
        val wallet = walletApplication.wallet

        val usernameInfo: CreateUsernameInfo
        try {
            log.info("deriveKey(handler, wallet)")
            val encryptionKey = deriveKey(handler, wallet)
            log.info("decryptSeedTask(handler, wallet, encryptionKey)")
            val seed = decryptSeedTask(handler, wallet, encryptionKey)
            usernameInfo = CreateUsernameInfo(username, seed, encryptionKey)
        } catch (ex: KeyCrypterException) {
            log.error(ex.message, ex)
            return
        }
        identityCreationState = IdentityCreationState(IdentityCreationState.State.PROCESSING_PAYMENT, false, username)

        val totalNumOfSteps = 4
        val appDatabase = AppDatabase.getAppDatabase()

        updateNotification(R.string.processing_home_title, R.string.processing_home_step_1, totalNumOfSteps, 1)
        updateState(appDatabase, IdentityCreationState.State.PROCESSING_PAYMENT, false)
        log.info("step1()")
        step1()

        updateNotification(R.string.processing_home_title, R.string.processing_home_step_2, totalNumOfSteps, 2)
        updateState(appDatabase, IdentityCreationState.State.CREATING_IDENTITY, false)
        log.info("step2()")
        step2()

        updateNotification(R.string.processing_home_title, R.string.processing_home_step_2, totalNumOfSteps, 3)
        updateState(appDatabase, IdentityCreationState.State.REGISTERING_USERNAME, false)
        log.info("step3()")
        step3()

        updateNotification(R.string.processing_done_subtitle, 0, 0, 0)
        updateState(appDatabase, IdentityCreationState.State.DONE, false)
        log.info("step4()")
        step4()
    }

    private suspend fun updateState(appDatabase: AppDatabase, newState: IdentityCreationState.State, error: Boolean) {
        withContext(Dispatchers.IO) {
            identityCreationState.state = newState
            identityCreationState.error = error
            appDatabase.identityCreationStateDao().insert(identityCreationState)
        }
    }

    /**
     * Wraps callbacks of DeriveKeyTask as Coroutine
     */
    private suspend fun deriveKey(handler: Handler, wallet: Wallet): KeyParameter {
        val password = securityGuard.retrievePassword()
        return suspendCoroutine { continuation ->
            object : DeriveKeyTask(handler, walletApplication.scryptIterationsTarget()) {

                override fun onSuccess(encryptionKey: KeyParameter, wasChanged: Boolean) {
                    continuation.resume(encryptionKey)
                }

                override fun onFailure(ex: KeyCrypterException?) {
                    log.error("unable to decrypt wallet", ex)
                    continuation.resumeWithException(ex as Throwable)
                }

            }.deriveKey(wallet, password)
        }
    }

    /**
     * Wraps callbacks of DecryptSeedTask as Coroutine
     */
    private suspend fun decryptSeedTask(handler: Handler, wallet: Wallet, encryptionKey: KeyParameter): DeterministicSeed {
        return suspendCoroutine { continuation ->
            object : DecryptSeedTask(handler) {
                override fun onSuccess(seed: DeterministicSeed) {
                    continuation.resume(seed)
                }

                override fun onBadPassphrase() {
                    throw RuntimeException("this should never happen in this scenario")
                }
            }.decryptSeed(wallet.activeKeyChain.seed, wallet.keyCrypter, encryptionKey)
        }
    }

    private suspend fun step1() {
        withContext(Dispatchers.IO) {
            Thread.sleep(7000)
        }
    }

    private suspend fun step2() {
        withContext(Dispatchers.IO) {
            Thread.sleep(7000)
        }
    }

    private suspend fun step3() {
        withContext(Dispatchers.IO) {
            Thread.sleep(7000)
        }
    }

    private suspend fun step4() {
        withContext(Dispatchers.IO) {
            Thread.sleep(7000)
        }
    }

    override fun onBind(intent: Intent): IBinder {
        TODO("Return the communication channel to the service.")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    private fun createNotification(@StringRes titleResId: Int, @StringRes messageResId: Int, progressMax: Int, progress: Int): Notification {
        return NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID_DASHPAY).run {
            setSmallIcon(R.drawable.ic_dash_d_white_bottom)
            setContentTitle(getString(titleResId))
            when {
                (progressMax == -1) -> setProgress(100, 0, true)
                (progressMax > 0) -> setProgress(progressMax, progress, false)
                else -> setProgress(0, 0, false)
            }
            if (messageResId != 0) {
                setContentText(getString(messageResId))
            }
            build()
        }
    }
}
