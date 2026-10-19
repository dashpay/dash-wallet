package de.schildbach.wallet.ui.more.connections

import com.google.gson.JsonParser
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.service.platform.PlatformService
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.more.connections.protocol.DashConnectUri
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Base58
import org.dashj.platform.dpp.contract.DataContract
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.dpp.identity.Identity
import org.junit.Assert.*
import org.junit.Test

class AppConnectOwnershipTest {
    private val service = mockk<PlatformService>(relaxed = true)
    private val repo = mockk<PlatformRepo>(relaxed = true)
    private val identities = mockk<IdentityRepository>(relaxed = true)
    private val config = mockk<DashConnectConfig>(relaxed = true)
    private val session = AppConnectSession(service, repo, identities, config)
    private val owner = Identifier.from(ByteArray(32) { 7 })
    private val identityId = Identifier.from(ByteArray(32) { 8 })
    private val identity = mockk<Identity>(relaxed = true)
    private val app = mockk<DataContract>(relaxed = true)
    private val profile = mockk<DataContract>(relaxed = true)
    private val vector = JsonParser.parseReader(javaClass.getResourceAsStream("/app-connect-vectors.json")!!.reader()).asJsonObject
    private val decoded = DashConnectUri.parseKeyRequest(vector.getAsJsonObject("request")["uri"].asString)
    private val request = decoded.copy(appConnect = decoded.appConnect!!.copy(createdAt = System.currentTimeMillis()))

    init {
        every { repo.walletApplication.wallet!!.watchingKey.pubKey } returns byteArrayOf(2) + ByteArray(32) { 1 }
        every { identities.blockchainIdentity!!.uniqueIdentifier } returns identityId
        every { identity.id } returns identityId
        every { service.platform.client.getIdentity(any(), true) } returns identity
        every { service.platform.client.refreshProtocolVersion() } returns 14
        every { service.platform.client.getPlatformTime(any()) } returns System.currentTimeMillis()
        every { app.ownerId } returns owner
        every { app.requiresIdentityEncryptionBoundedKey } returns true
        every { app.requiresIdentityDecryptionBoundedKey } returns true
        every { profile.ownerId } returns Identifier.from(ByteArray(32))
        every { service.platform.client.getDataContract(any(), true) } answers {
            if ((firstArg<ByteArray>()).contentEquals(request.contractId)) app else profile
        }
        every { service.platform.client.getContractGroupOwner(any()) } returns owner
        coEvery { config.getGrants() } returns emptyList()
    }

    @Test fun `owned group plus exact DashPay profile exception prepare successfully`() = runBlocking {
        val plan = session.prepare(request)
        assertEquals(2, plan.grants.size)
        assertEquals("profile", plan.grants[1].documentType)
        plan.requireMatches(request)
        verify { service.platform.client.getDataContract(request.contractId, true) }
        Unit
    }

    @Test fun `foreign group ownership is rejected`() {
        every { service.platform.client.getContractGroupOwner(any()) } returns identityId
        assertThrows(IllegalStateException::class.java) { runBlocking { session.prepare(request) } }
    }

    @Test fun `DashPay exception does not permit contact requests or whole contract`() {
        val raw = request.appConnect!!
        val changed = raw.auth[1].copy(documentType = "contactRequest")
        assertThrows(IllegalStateException::class.java) {
            runBlocking { session.prepare(request.copy(appConnect = raw.copy(auth = listOf(raw.auth[0], changed)))) }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { session.prepare(request.copy(appConnect = raw.copy(auth = listOf(raw.auth[0], changed.copy(kind = 0, documentType = null))))) }
        }
    }

    @Test fun `encryption opt in and app binding are mandatory`() {
        every { app.requiresIdentityEncryptionBoundedKey } returns false
        assertThrows(IllegalStateException::class.java) { runBlocking { session.prepare(request) } }
        every { app.requiresIdentityEncryptionBoundedKey } returns true
        val raw = request.appConnect!!
        assertThrows(IllegalStateException::class.java) {
            runBlocking { session.prepare(request.copy(appConnect = raw.copy(encryption = listOf(raw.encryption[0].copy(contractId = ByteArray(32)))))) }
        }
    }

    @Test fun `system apps old protocols and different identity hints are rejected`() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { session.prepare(request.copy(contractId = Base58.decode(AppConnectSession.RESPONSE_CONTRACT))) }
        }
        every { service.platform.client.refreshProtocolVersion() } returns 13
        assertThrows(IllegalStateException::class.java) { runBlocking { session.prepare(request) } }
        every { service.platform.client.refreshProtocolVersion() } returns 14
        assertThrows(IllegalStateException::class.java) {
            runBlocking { session.prepare(request.copy(appConnect = request.appConnect!!.copy(identityHint = ByteArray(32)))) }
        }
    }

    @Test fun `consent cannot remove limits raise budgets or broaden bounds`() = runBlocking {
        val plan = session.prepare(request)
        val primary = plan.grants.first()
        for (bad in listOf(primary.copy(totalBudget = null), primary.copy(totalBudget = primary.totalBudget!! + java.math.BigInteger.ONE),
            primary.copy(expiresAt = null), primary.copy(kind = 0))) {
            assertThrows(IllegalArgumentException::class.java) {
                plan.copy(grants = listOf(bad) + plan.grants.drop(1)).requireMatches(request)
            }
        }
    }

    @Test fun `stale wallet scope cannot revoke or inspect a new wallet`() {
        assertThrows(IllegalStateException::class.java) { runBlocking { session.disconnect("app", "previous-wallet-scope") } }
        assertThrows(IllegalStateException::class.java) { runBlocking { session.details("app", "previous-wallet-scope") } }
        coVerify(exactly = 0) { config.getGrants() }
        verify(exactly = 0) { service.platform.client.getIdentity(any(), any()) }
    }
}
