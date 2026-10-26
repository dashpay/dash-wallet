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
import org.dashj.platform.dpp.contract.VerifiedContractGroup
import org.dashj.platform.dpp.contract.ContractGroupDocumentMember
import org.dashj.platform.dpp.contract.ContractGroupTokenMember
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.dpp.identity.Identity
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.Assume.assumeFalse
import de.schildbach.wallet.Constants
import org.bitcoinj.core.NetworkParameters

class AppConnectOwnershipTest {
    private fun nonMainnetOnly() {
        assumeFalse(Constants.IS_PROD_BUILD || Constants.NETWORK_PARAMETERS.id == NetworkParameters.ID_MAINNET)
    }
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
    private val groupRequest = decoded.copy(appConnect = decoded.appConnect!!.copy(createdAt = System.currentTimeMillis()))
    private val request = groupRequest.copy(appConnect = groupRequest.appConnect!!.let { raw ->
        raw.copy(auth = listOf(raw.auth.first().copy(kind = 0, securityLevel = 2, id = groupRequest.contractId)) + raw.auth.drop(1))
    })
    private val group = VerifiedContractGroup(Identifier.from(groupRequest.appConnect!!.auth.first().id), owner,
        setOf(identityId), null, null, setOf(Identifier.from(request.contractId)),
        setOf(ContractGroupDocumentMember(Identifier.from(request.contractId), "profile")),
        setOf(ContractGroupTokenMember(Identifier.from(request.contractId), 0)))

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
        every { service.platform.client.getContractGroup(any()) } returns group
        coEvery { config.getGrants() } returns emptyList()
    }

    @Test fun `owned contract plus exact DashPay profile exception prepare successfully`() = runBlocking {
        nonMainnetOnly()
        val plan = session.prepare(request)
        assertEquals(2, plan.grants.size)
        assertEquals("profile", plan.grants[1].documentType)
        plan.requireMatches(request)
        verify { service.platform.client.getDataContract(request.contractId, true) }
        Unit
    }

    @Test fun `legacy preparation and approval retries fail before any identity or signing access`() {
        val legacy = request.copy(appConnect = null)
        val plan = AppConnectPlan("hash", "scope", "identity", request.requireAppConnect().auth, "Consent")
        val error = assertThrows(de.schildbach.wallet.ui.more.connections.protocol.DashConnectUriException::class.java) {
            runBlocking { session.prepare(legacy) }
        }
        assertEquals(DashConnectUri.LEGACY_KEY_REQUEST_ERROR, error.message)
        for (recovering in listOf(false, true)) {
            val failure = assertThrows(de.schildbach.wallet.ui.more.connections.protocol.DashConnectUriException::class.java) {
                runBlocking(ConnectionAuthorization { true }) { session.approve(legacy, plan.copy(recovering = recovering)) }
            }
            assertEquals(DashConnectUri.LEGACY_KEY_REQUEST_ERROR, failure.message)
        }
        assertThrows(de.schildbach.wallet.ui.more.connections.protocol.DashConnectUriException::class.java) {
            plan.requireMatches(legacy)
        }
        verify(exactly = 0) { service.platform.client.refreshProtocolVersion() }
        verify(exactly = 0) { service.platform.client.getIdentity(any(), any()) }
        coVerify(exactly = 0) { repo.getWalletEncryptionKey() }
        coVerify(exactly = 0) { repo.getWalletSeed() }
        coVerify(exactly = 0) { config.getGrants() }
        coVerify(exactly = 0) { config.saveGrant(any()) }
    }

    @Test fun `verified admin managed group prepares with complete dynamic scope disclosure`() = runBlocking {
        nonMainnetOnly()
        val client = service.platform.client
        val plan = session.prepare(groupRequest)
        assertEquals(group, plan.groupScopes[group.id.toString()])
        val description = plan.choices().first().description
        for (text in listOf(owner.toString(), identityId.toString(), "Document:", "position 0", "current AND future", "without another wallet approval")) {
            assertTrue(description, description.contains(text))
        }
        plan.requireGroupScopes(mapOf(group.id.toString() to group))
        assertEquals(owner.toString(), plan.groupScopesJson().getJSONObject(group.id.toString()).getString("ownerId"))
        verify(exactly = 0) { client.getContractGroupOwner(any()) }
    }

    @Test fun `changed group scope blocks approval and retry before accessing seed or saving keys`() = runBlocking {
        nonMainnetOnly()
        val plan = session.prepare(groupRequest)
        for (changed in listOf(group.copy(adminIds = emptySet()), group.copy(contracts = emptySet()),
            group.copy(documentTypes = emptySet()), group.copy(tokens = emptySet()))) {
            every { service.platform.client.getContractGroup(any()) } returns changed
            for (recovering in listOf(false, true)) {
                assertThrows(GroupScopeChangedException::class.java) {
                    runBlocking(ConnectionAuthorization { true }) { session.approve(groupRequest, plan.copy(recovering = recovering)) }
                }
            }
        }
        coVerify(exactly = 0) { repo.getWalletSeed() }
        coVerify(exactly = 0) { repo.getWalletEncryptionKey() }
        coVerify(exactly = 0) { config.saveGrant(any()) }
    }

    @Test fun `missing foreign or unverified groups fail closed`() {
        nonMainnetOnly()
        every { service.platform.client.getContractGroup(any()) } returns null
        assertThrows(IllegalStateException::class.java) {
            runBlocking { session.prepare(groupRequest) }
        }
        every { service.platform.client.getContractGroup(any()) } returns group.copy(ownerId = identityId)
        assertThrows(IllegalStateException::class.java) { runBlocking { session.prepare(groupRequest) } }
        every { service.platform.client.getContractGroup(any()) } throws IllegalStateException("Invalid proof")
        assertThrows(IllegalStateException::class.java) { runBlocking { session.prepare(groupRequest) } }
        coVerify(exactly = 0) { config.saveGrant(any()) }
    }

    @Test fun `optional unselected group changes do not alter selected consent`() = runBlocking {
        nonMainnetOnly()
        val raw = request.appConnect!!
        val optionalGroup = groupRequest.appConnect!!.auth.first()
        val plan = session.prepare(request.copy(appConnect = raw.copy(auth = raw.auth + optionalGroup)))
        plan.copy(selectedAuth = setOf(0)).requireGroupScopes(emptyMap())
        assertThrows(GroupScopeChangedException::class.java) {
            plan.requireGroupScopes(emptyMap())
        }
        Unit
    }

    @Test fun `foreign single contract ownership is still rejected`() {
        nonMainnetOnly()
        every { app.ownerId } returns owner
        val raw = request.appConnect!!
        val foreign = raw.auth.first().copy(id = ByteArray(32) { 3 })
        assertThrows(IllegalStateException::class.java) {
            runBlocking { session.prepare(request.copy(appConnect = raw.copy(auth = listOf(foreign) + raw.auth.drop(1)))) }
        }
    }

    @Test fun `DashPay exception does not permit contact requests or whole contract`() {
        nonMainnetOnly()
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
        nonMainnetOnly()
        every { app.requiresIdentityEncryptionBoundedKey } returns false
        assertThrows(IllegalStateException::class.java) { runBlocking { session.prepare(request) } }
        every { app.requiresIdentityEncryptionBoundedKey } returns true
        val raw = request.appConnect!!
        assertThrows(IllegalStateException::class.java) {
            runBlocking { session.prepare(request.copy(appConnect = raw.copy(encryption = listOf(raw.encryption[0].copy(contractId = ByteArray(32)))))) }
        }
    }

    @Test fun `system apps old protocols and different identity hints are rejected`() {
        nonMainnetOnly()
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
        nonMainnetOnly()
        val plan = session.prepare(request)
        val primary = plan.grants.first()
        for (bad in listOf(primary.copy(totalBudget = null), primary.copy(totalBudget = primary.totalBudget!! + java.math.BigInteger.ONE),
            primary.copy(expiresAt = null), primary.copy(kind = 1, documentType = "profile"))) {
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
