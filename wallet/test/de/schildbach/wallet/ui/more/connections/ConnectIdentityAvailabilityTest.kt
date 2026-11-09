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
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class ConnectIdentityAvailabilityTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val initialized = MutableStateFlow<Result<Unit>?>(Result.success(Unit))
    private val identity = MutableStateFlow<BlockchainIdentity?>(null)
    private val identities = mockk<IdentityRepository>(relaxed = true)
    private val wallet = mockk<WalletDataProvider>(relaxed = true)

    init {
        every { identities.initializationResult } returns initialized
        every { identities.blockchainIdentityFlow } returns identity
        every { wallet.wallet } returns mockk(relaxed = true)
    }

    private inner class Fixture(files: File = temporaryFolder.newFolder()) {
        private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext() = this
            override fun getFilesDir() = files
        }
        val identityConfig = BlockchainIdentityConfig(context, wallet, mockk(relaxed = true))
        val audits = DashConnectConfig(context, wallet)
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
        every { wallet.wallet } returns mockk(relaxed = true)
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
        grant.audits.saveGrant(JSONObject().put("id", "restored-grant"))
        assertEquals(ConnectIdentityStatus.AVAILABLE, grant.status())
        val legacy = Fixture()
        legacy.audits.upsertConnection(StoredConnection("app", "Old", "", "approved", 1))
        assertEquals(ConnectIdentityStatus.AVAILABLE, legacy.status())
        val purchase = Fixture()
        purchase.audits.savePurchase(JSONObject().put("id", "uncertain-purchase"))
        assertEquals(ConnectIdentityStatus.AVAILABLE, purchase.status())
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
