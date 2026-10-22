package de.schildbach.wallet.ui.more.connections

import android.app.Application
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.service.platform.PlatformService
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.dashj.platform.dpp.contract.DataContract
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.dpp.identity.*
import org.dashj.platform.sdk.KeyType
import org.dashj.platform.sdk.Purpose
import org.dashj.platform.sdk.SecurityLevel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE,
    shadows = [AppConnectKeyStateTest.ShadowSdkEnums::class])
class RevocationOwnershipTest {
    private val service = mockk<PlatformService>(relaxed = true)
    private val wallet = mockk<PlatformRepo>(relaxed = true)
    private val identities = mockk<IdentityRepository>(relaxed = true)
    private val config = mockk<DashConnectConfig>(relaxed = true)
    private val appId = Identifier.from(ByteArray(32) { 9 })
    private val target = Identifier.from(ByteArray(32) { 11 })
    private val owner = Identifier.from(ByteArray(32) { 13 })
    private val identityId = Identifier.from(ByteArray(32) { 15 })
    private val session = AppConnectSession(service, wallet, identities, config)

    private fun setup(group: Boolean = false) {
        every { wallet.walletApplication.wallet!!.watchingKey.pubKey } returns ByteArray(33) { 1 }
        every { identities.blockchainIdentity!!.uniqueIdentifier } returns identityId
        val key = IdentityPublicKey(7, KeyType.ECDSA_HASH160, Purpose.AUTHENTICATION, SecurityLevel.HIGH,
            if (group) ContractGroupBounds(target) else SingleContractBounds(target), ByteArray(20), false)
        val identity = org.dashj.platform.dpp.identity.Identity(identityId, listOf(key), 100, 0, 14)
        every { service.platform.client.getIdentity(any(), true) } returns identity
        every { service.platform.client.getPlatformTime(any()) } returns 100
        every { service.platform.client.getDataContract(appId.toBuffer(), true) } returns mockk<DataContract> {
            every { ownerId } returns owner
        }
        coEvery { config.getGrants() } returns emptyList()
    }

    @Test fun `absent sibling ownership cannot establish complete revocation`() {
        setup()
        every { service.platform.client.getDataContract(target.toBuffer(), true) } returns null
        assertThrows(IllegalStateException::class.java) { runBlocking { session.details(appId.toString(), session.scope()) } }
        coVerify(exactly = 0) { config.saveGrant(any()) }
    }

    @Test fun `failed sibling proof cannot establish complete revocation`() {
        setup()
        every { service.platform.client.getDataContract(target.toBuffer(), true) } throws IllegalStateException("invalid proof")
        val error = assertThrows(IllegalStateException::class.java) { runBlocking { session.details(appId.toString(), session.scope()) } }
        assertEquals("invalid proof", error.message)
        coVerify(exactly = 0) { config.saveGrant(any()) }
    }

    @Test fun `missing group owner fails closed`() {
        setup(group = true)
        every { service.platform.client.getContractGroupOwner(target) } returns null
        assertThrows(IllegalStateException::class.java) { runBlocking { session.details(appId.toString(), session.scope()) } }
    }

    @Test fun `proved same owner reports the surviving key`() = runBlocking {
        setup()
        every { service.platform.client.getDataContract(target.toBuffer(), true) } returns mockk<DataContract> {
            every { ownerId } returns owner
        }
        assertTrue(session.details(appId.toString(), session.scope()).contains("Other live application keys: 7"))
    }
}
