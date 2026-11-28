package de.schildbach.wallet.ui.more.connections

import android.app.Application
import android.content.ContextWrapper
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.service.platform.IdentityRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bitcoinj.core.NetworkParameters
import org.dash.wallet.common.WalletDataProvider
import org.dashj.platform.dashpay.BlockchainIdentity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class ConnectIdentityAvailabilityTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val initialized = MutableStateFlow<Result<Unit>?>(Result.success(Unit))
    private val identity = MutableStateFlow<BlockchainIdentity?>(null)
    private val identities = mockk<IdentityRepository>(relaxed = true)
    private val wallet = mockk<de.schildbach.wallet.data.WalletData>(relaxed = true)
    private val walletProvider = mockk<WalletDataProvider>(relaxed = true)

    private var watchingKey = ByteArray(33) { 1 }
    private val dashjWallet = mockk<org.bitcoinj.wallet.Wallet>(relaxed = true)
    /** The current wallet's scope, as [AppConnectSession.scope] writes it, with an identity. */
    private val ownScope get() = "${connectWalletScope(dashjWallet)}:identity"

    init {
        every { identities.initializationResult } returns initialized
        every { identities.blockchainIdentityFlow } returns identity
        every { dashjWallet.watchingKey.pubKey } answers { watchingKey }
        every { wallet.wallet } returns dashjWallet
    }

    private inner class Fixture(files: File = temporaryFolder.newFolder()) {
        private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext() = this
            override fun getFilesDir() = files
        }
        val identityConfig = BlockchainIdentityConfig(context, walletProvider, mockk(relaxed = true))
        val audits = DashConnectConfig(context, walletProvider)
        val availability = ConnectIdentityAvailability(identities, identityConfig, audits, wallet)
        suspend fun status() = withTimeout(5_000) { availability.observe().first { it != ConnectIdentityStatus.LOADING } }
    }

    @Test fun `successful initialization and two readable empty stores confirm absence`() = runBlocking {
        assertEquals(ConnectIdentityStatus.NO_IDENTITY, Fixture().status())
    }

    @Test fun `pending or failed identity initialization never confirms absence`() = runBlocking {
        val fixture = Fixture()
        initialized.value = null
        assertEquals(ConnectIdentityStatus.LOADING, withTimeout(5_000) { fixture.availability.observe().drop(1).first() })
        initialized.value = Result.failure(IllegalStateException("Unreadable identity"))
        assertEquals(ConnectIdentityStatus.UNAVAILABLE, fixture.status())
    }

    @Test fun `missing wallet or partial identity cannot be mistaken for a new wallet`() = runBlocking {
        val fixture = Fixture()
        every { wallet.wallet } returns null
        assertEquals(ConnectIdentityStatus.UNAVAILABLE, fixture.status())
        every { wallet.wallet } returns dashjWallet
        identity.value = mockk(relaxed = true)
        assertEquals(ConnectIdentityStatus.AVAILABLE, fixture.status())
    }

    @Test fun `persisted identity without an in memory object retains recovery access`() = runBlocking {
        val fixture = Fixture()
        fixture.identityConfig.set(BlockchainIdentityConfig.IDENTITY_ID, "saved-identity")
        assertEquals(ConnectIdentityStatus.AVAILABLE, fixture.status())
    }

    @Test fun `grant legacy and purchase audits independently retain recovery access`() = runBlocking {
        val grant = Fixture()
        grant.audits.saveGrant(JSONObject().put("id", "restored-grant").put("scope", ownScope))
        assertEquals(ConnectIdentityStatus.AVAILABLE, grant.status())
        val legacy = Fixture()
        legacy.audits.upsertConnection(StoredConnection("app", "Old", "", "approved", 1))
        assertEquals(ConnectIdentityStatus.AVAILABLE, legacy.status())
        val purchase = Fixture()
        purchase.audits.savePurchase(JSONObject().put("id", "uncertain-purchase").put("scope", ownScope))
        assertEquals(ConnectIdentityStatus.AVAILABLE, purchase.status())
    }

    @Test fun `a wiped wallet's retained records do not make a new wallet look connected`() = runBlocking {
        val fixture = Fixture()
        val wipedScope = ownScope
        fixture.audits.saveGrant(JSONObject().put("id", "$wipedScope:grant").put("scope", wipedScope))
        fixture.audits.savePurchase(JSONObject().put("id", "$wipedScope:purchase").put("scope", wipedScope))
        // A new seed after the wipe: the records are kept for a restore, but are not this wallet's.
        watchingKey = ByteArray(33) { 2 }
        assertEquals(ConnectIdentityStatus.NO_IDENTITY, fixture.status())
        // A scope that only extends this wallet's hash is another wallet's too.
        val newScope = connectWalletScope(dashjWallet)
        fixture.audits.saveGrant(JSONObject().put("id", "prefix").put("scope", "${newScope}0:identity"))
        assertEquals(ConnectIdentityStatus.NO_IDENTITY, fixture.status())
        // Restoring the wiped seed makes them count again.
        watchingKey = ByteArray(33) { 1 }
        assertEquals(ConnectIdentityStatus.AVAILABLE, fixture.status())
    }

    @Test fun `the same wallet's records on another network do not count`() = runBlocking {
        val fixture = Fixture()
        val current = de.schildbach.wallet.Constants.NETWORK_PARAMETERS.id
        val other = if (current == NetworkParameters.ID_MAINNET) NetworkParameters.ID_TESTNET else NetworkParameters.ID_MAINNET
        assertTrue(ownScope.startsWith("$current:"))
        val otherNetwork = "$other:${ownScope.removePrefix("$current:")}"
        fixture.audits.saveGrant(JSONObject().put("id", "grant").put("scope", otherNetwork))
        assertEquals(ConnectIdentityStatus.NO_IDENTITY, fixture.status())
    }

    @Test fun `a retained record without a scope is unreadable, not another wallet's`() = runBlocking {
        val grant = Fixture()
        grant.audits.saveGrant(JSONObject().put("id", "unscoped"))
        assertEquals(ConnectIdentityStatus.UNAVAILABLE, grant.status())
        val purchase = Fixture()
        purchase.audits.savePurchase(JSONObject().put("id", "unscoped"))
        assertEquals(ConnectIdentityStatus.UNAVAILABLE, purchase.status())
    }

    @Test fun `incomplete registration metadata cannot be declared absent`() = runBlocking {
        val fixture = Fixture()
        fixture.identityConfig.set(BlockchainIdentityConfig.CREATION_STATE, "NONE")
        assertEquals(ConnectIdentityStatus.NO_IDENTITY, fixture.status())
        fixture.identityConfig.set(BlockchainIdentityConfig.IDENTITY_REGISTRATION_STATUS, "REGISTERED")
        assertEquals(ConnectIdentityStatus.AVAILABLE, fixture.status())
    }

    @Test fun `identity and audit storage IO failures never confirm absence`() = runBlocking {
        for (name in listOf(BlockchainIdentityConfig.PREFERENCES_NAME, DashConnectConfig.PREFERENCES_NAME)) {
            val files = temporaryFolder.newFolder()
            assertTrue(File(files, "datastore/$name.preferences_pb").mkdirs())
            assertEquals(ConnectIdentityStatus.UNAVAILABLE, Fixture(files).status())
        }
    }

    @Test fun `malformed identity state and audit JSON never confirm absence`() = runBlocking {
        val identity = Fixture()
        identity.identityConfig.set(BlockchainIdentityConfig.CREATION_STATE, "invalid-state")
        assertEquals(ConnectIdentityStatus.UNAVAILABLE, identity.status())
        for (key in listOf("app_connect_grants_v2", "connections", "dash_st_purchases")) {
            val fixture = Fixture()
            fixture.audits.set(androidx.datastore.preferences.core.stringPreferencesKey(key), "invalid-json")
            assertEquals(ConnectIdentityStatus.UNAVAILABLE, fixture.status())
        }
    }
}
