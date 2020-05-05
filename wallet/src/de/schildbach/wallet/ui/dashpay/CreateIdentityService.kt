package de.schildbach.wallet.ui.dashpay

import android.content.Context
import android.content.Intent
import android.os.Handler
import androidx.lifecycle.LifecycleService
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.IdentityCreationState
import de.schildbach.wallet.ui.security.SecurityGuard
import de.schildbach.wallet.ui.send.DecryptSeedTask
import de.schildbach.wallet.ui.send.DeriveKeyTask
import kotlinx.coroutines.*
import org.bitcoinj.crypto.KeyCrypterException
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.Wallet
import org.bouncycastle.crypto.params.KeyParameter
import org.dashevo.dashpay.BlockchainIdentity
import org.dashevo.dpp.identity.Identity
import org.slf4j.LoggerFactory
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.random.Random


class CreateIdentityService : LifecycleService() {

    companion object {
        private val log = LoggerFactory.getLogger(CreateIdentityService::class.java)

        private const val ACTION_CREATE_IDENTITY = "org.dash.dashpay.action.CREATE_IDENTITY"

        private const val EXTRA_USERNAME = "org.dash.dashpay.extra.USERNAME"

        @JvmStatic
        fun createIntent(context: Context, username: String): Intent {
            return Intent(context, CreateIdentityService::class.java).apply {
                action = ACTION_CREATE_IDENTITY
                putExtra(EXTRA_USERNAME, username)
            }
        }
    }

    private val walletApplication by lazy { application as WalletApplication }
    private val platformRepo by lazy { PlatformRepo(walletApplication) }
    private lateinit var securityGuard: SecurityGuard

    private val identityCreationStateDaoAsync = AppDatabase.getAppDatabase().identityCreationStateDaoAsync()

    private val createIdentityNotification by lazy { CreateIdentityNotification(this) }

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
        createIdentityNotification.startServiceForeground()
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

        val exceptionHandler = CoroutineExceptionHandler { _, exception ->
            log.error("[${identityCreationState.state}(error)]", exception)
            GlobalScope.launch {
                updateState(identityCreationState.state, true)
//                stopSelf()
            }
        }

        serviceScope.launch(exceptionHandler) {
            createIdentity(username)
            stopSelf()
        }
    }

    private suspend fun createIdentity(username: String) {

        //identityCreationState = identityCreationStateDaoAsync.load()
        //        ?: IdentityCreationState(IdentityCreationState.State.UPGRADING_WALLET, false, username)
        identityCreationState = IdentityCreationState(IdentityCreationState.State.UPGRADING_WALLET, false, username)
        identityCreationStateDaoAsync.insert(identityCreationState)

        if (identityCreationState.state != IdentityCreationState.State.UPGRADING_WALLET || identityCreationState.error) {
            log.info("resuming identity creation process [${identityCreationState.state}${if (identityCreationState.error) "(error)" else ""}]")
        }

        val handler = Handler()
        val wallet = walletApplication.wallet
        val password = securityGuard.retrievePassword()

        val encryptionKey = deriveKey(handler, wallet, password)
        val seed = decryptSeed(handler, wallet, encryptionKey)

//        val usernameInfo = CreateUsernameInfo(username, seed, encryptionKey)


        platformRepo.addWalletAuthenticationKeysAsync(seed, encryptionKey)

        updateState(IdentityCreationState.State.CREDIT_FUNDING_TX_SENDING)

        //create the Blockchain Identity object (this needs to be saved somewhere eventually)
        val blockchainIdentity = BlockchainIdentity(Identity.IdentityType.USER, 0, wallet)

        platformRepo.createCreditFundingTransactionAsync(blockchainIdentity, encryptionKey)

        walletApplication.broadcastTransaction(blockchainIdentity.creditFundingTransaction)

        updateState(IdentityCreationState.State.CREDIT_FUNDING_TX_SENT)

        println("step2")
        updateState(IdentityCreationState.State.CREDIT_FUNDING_TX_CONFIRMED)
        delay2s()

        println("step3")
        updateState(IdentityCreationState.State.IDENTITY_REGISTERING)
        delay2s()

        updateState(IdentityCreationState.State.IDENTITY_REGISTERING)

        platformRepo.registerIdentityAsync(blockchainIdentity, encryptionKey)

        delay2s()

        updateState(IdentityCreationState.State.IDENTITY_REGISTERED)
        platformRepo.verifyIdentityRegisteredAsync(blockchainIdentity)
        delay2s()

        updateState(IdentityCreationState.State.PREORDER_REGISTERING)
        blockchainIdentity.addUsername(username)

        platformRepo.preorderNameAsync(blockchainIdentity, encryptionKey)
        delay2s()

        updateState(IdentityCreationState.State.PREORDER_REGISTERED)
        platformRepo.isNamePreorderedAsync(blockchainIdentity)
        delay2s()

        updateState(IdentityCreationState.State.USERNAME_REGISTERING)
        platformRepo.registerNameAsync(blockchainIdentity, encryptionKey)
        delay2s()

        updateState(IdentityCreationState.State.USERNAME_REGISTERED)
        platformRepo.isNameRegisteredAsync(blockchainIdentity)
        delay2s()

        // aaaand we're done :)
    }

    private suspend fun updateState(newState: IdentityCreationState.State, error: Boolean = false) {
        identityCreationState.state = newState
        identityCreationState.error = error
        identityCreationStateDaoAsync.insert(identityCreationState)
    }

    /**
     * Wraps callbacks of DeriveKeyTask as Coroutine
     */
    private suspend fun deriveKey(handler: Handler, wallet: Wallet, password: String): KeyParameter {
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
                    continuation.resumeWithException(IOException("this should never happen in this scenario"))

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
}
