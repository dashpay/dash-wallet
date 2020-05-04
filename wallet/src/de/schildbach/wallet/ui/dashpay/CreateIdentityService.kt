package de.schildbach.wallet.ui.dashpay

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.Observer
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


class CreateIdentityService : LifecycleService() {

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

        AppDatabase.getAppDatabase().identityCreationStateDao().load().observe(this, Observer {
            when (it?.state) {
                IdentityCreationState.State.UPGRADING_WALLET,
                IdentityCreationState.State.CREDIT_FUNDING_TX_CREATING,
                IdentityCreationState.State.CREDIT_FUNDING_TX_SENDING,
                IdentityCreationState.State.CREDIT_FUNDING_TX_SENT,
                IdentityCreationState.State.CREDIT_FUNDING_TX_CONFIRMED -> {
                    updateNotification(R.string.processing_home_title, R.string.processing_home_step_1, 4, 1)
                }
                IdentityCreationState.State.IDENTITY_REGISTERING,
                IdentityCreationState.State.IDENTITY_REGISTERED -> {
                    updateNotification(R.string.processing_home_title, R.string.processing_home_step_2, 4, 2)
                }
                IdentityCreationState.State.PREORDER_REGISTERING,
                IdentityCreationState.State.PREORDER_REGISTERED,
                IdentityCreationState.State.USERNAME_REGISTERING -> {
                    updateNotification(R.string.processing_home_title, R.string.processing_home_step_3, 4, 3)
                }
                IdentityCreationState.State.USERNAME_REGISTERED -> {
                    updateNotification(R.string.processing_done_subtitle, 0, 0, 0)
                }
            }
        })
    }

    private fun updateNotification(@StringRes titleResId: Int, @StringRes messageResId: Int, progressMax: Int, progress: Int) {
        val notification: Notification = createNotification(titleResId, messageResId, progressMax, progress)
        notificationManager.notify(Constants.NOTIFICATION_ID_DASHPAY_CREATE_IDENTITY, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

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
        val encryptionKey: KeyParameter
        val seed: DeterministicSeed
        try {
            encryptionKey = deriveKey(handler, wallet)
            seed = decryptSeed(handler, wallet, encryptionKey)
            usernameInfo = CreateUsernameInfo(username, seed, encryptionKey)
        } catch (ex: KeyCrypterException) {
            log.error(ex.message, ex)
            return
        }
        val appDatabase = AppDatabase.getAppDatabase()

        identityCreationState = IdentityCreationState(IdentityCreationState.State.UPGRADING_WALLET, false, username)
        updateState(appDatabase, IdentityCreationState.State.UPGRADING_WALLET)

/*         THIS ONLY COMMENTED OUT TO SIMPLIFY TESTING OF UI STATES, BELOW CODE SHOULD WORK CORRECTLY

        try {
            platformRepo.addWalletAuthenticationKeysAsync(seed, encryptionKey)
        } catch (ex: Exception) {
            updateStateWithError(appDatabase)
            log.error(ex.message, ex)
            return
        }

        updateState(appDatabase, IdentityCreationState.State.CREDIT_FUNDING_TX_SENDING)

        //create the Blockchain Identity object (this needs to be saved somewhere eventually)
        val blockchainIdentity = BlockchainIdentity(Identity.IdentityType.USER, 0, wallet)
        try {
            platformRepo.createCreditFundingTransactionAsync(blockchainIdentity)
        } catch (ex: Exception) {
            updateStateWithError(appDatabase)
            log.error(ex.message, ex)
            return
        }
*/

        updateState(appDatabase, IdentityCreationState.State.CREDIT_FUNDING_TX_SENT)
        delay2s()

        updateState(appDatabase, IdentityCreationState.State.CREDIT_FUNDING_TX_CONFIRMED)
        delay2s()

        updateState(appDatabase, IdentityCreationState.State.IDENTITY_REGISTERING)
        delay2s()

        updateState(appDatabase, IdentityCreationState.State.IDENTITY_REGISTERED)
        delay2s()

        updateState(appDatabase, IdentityCreationState.State.PREORDER_REGISTERING)
        delay2s()

        updateState(appDatabase, IdentityCreationState.State.PREORDER_REGISTERED)
        delay2s()

        updateState(appDatabase, IdentityCreationState.State.USERNAME_REGISTERING)
        delay2s()

        updateState(appDatabase, IdentityCreationState.State.USERNAME_REGISTERED)
        delay2s()

        // aaaand we're done :)
    }

    private suspend fun updateStateWithError(appDatabase: AppDatabase) {
        updateState(appDatabase, identityCreationState.state, true)
    }

    private suspend fun updateState(appDatabase: AppDatabase, newState: IdentityCreationState.State, error: Boolean = false) {
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
    private suspend fun decryptSeed(handler: Handler, wallet: Wallet, encryptionKey: KeyParameter): DeterministicSeed {
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

    private suspend fun delay2s() {
        withContext(Dispatchers.IO) {
            delay(2000)
        }
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
