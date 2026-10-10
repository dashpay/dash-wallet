/*
 * Copyright 2026 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.schildbach.wallet.service.platform

import de.schildbach.wallet.database.dao.DashPayContactRequestDao
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.database.entity.DashPayContactRequest
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.service.platform.sdk.SdkProfileQueries
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bitcoinj.core.Base58
import org.bitcoinj.core.Context
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.wallet.Wallet
import org.dashj.platform.dpp.contract.DataContract
import org.dashj.platform.dpp.document.Document
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.sdk.client.ClientAppDefinition
import org.dashj.platform.sdk.platform.Contracts
import org.dashj.platform.sdk.platform.Documents
import org.dashj.platform.sdk.platform.Platform
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End to end through [PlatformSynchronizationService.updateContactRequests]:
 * the integrity pass must actually RUN in the post-cutover state the field
 * was stuck in — the dashpay app registered but its data contract never
 * loaded (12.0.0-qa28/qa30: 831 passes, 831 "skipped — the dashpay data
 * contract is not loaded yet", 0 runs).
 */
class PlatformSyncIntegrityPassTest {

    private val ownId = Base58.encode(ByteArray(32) { 3 })
    private val contactId = Base58.encode(ByteArray(32) { 5 })
    private val dashpayContractId = Identifier.from(ByteArray(32) { 9 })

    /** A request we sent whose key chain an earlier pass skipped (lookup timed out). */
    private val sentRow = DashPayContactRequest(ownId, contactId, 0, ByteArray(96), 0, 1, 1L, null, null)

    private val wallet = mockk<Wallet>(relaxed = true) {
        every { context } returns Context(TestNet3Params.get())
        every { hasReceivingKeyChain(any()) } returns false
    }
    private val walletApplication = mockk<de.schildbach.wallet.WalletApplication>(relaxed = true) {
        every { wallet } returns this@PlatformSyncIntegrityPassTest.wallet
    }
    private val identityData = mockk<BlockchainIdentityData>(relaxed = true) {
        every { creationState } returns IdentityCreationState.DONE
        every { username } returns "tester"
        every { userId } returns ownId
    }
    private val identityRepository = mockk<IdentityRepository>(relaxed = true) {
        every { hasBlockchainIdentity } returns true
    }

    // The legacy dashj Platform exactly as the field had it: app registered, contract null.
    private val dashpayApp = ClientAppDefinition(dashpayContractId)
    private val contracts = mockk<Contracts>()
    private val contactDocument = mockk<Document>(relaxed = true) {
        every { data } returns mutableMapOf<String, Any?>("toUserId" to Identifier.from(contactId))
        every { ownerId } returns Identifier.from(ownId)
    }
    private val documents = mockk<Documents> {
        every { create(any(), any(), any()) } returns contactDocument
    }
    private val legacyPlatform = mockk<Platform> {
        every { apps } returns hashMapOf("dashpay" to dashpayApp)
        every { this@mockk.contracts } returns this@PlatformSyncIntegrityPassTest.contracts
        every { this@mockk.documents } returns this@PlatformSyncIntegrityPassTest.documents
    }

    private val contactRequests = mockk<org.dashj.platform.dashpay.ContactRequests> {
        every { get(any<String>(), any(), any(), any(), null) } returns emptyList()
    }
    private val platform = mockk<PlatformService>(relaxed = true) {
        every { hasApp("dashpay") } returns true
        every { contactRequests } returns this@PlatformSyncIntegrityPassTest.contactRequests
        every { platform } returns legacyPlatform
        // The contact's identity lookup still fails this pass: the pass must
        // still have TRIED it, and must not have built or added anything else.
        every { getContactIdentity(any<Identifier>()) } returns null
    }
    private val platformRepo = mockk<de.schildbach.wallet.ui.dashpay.PlatformRepo>(relaxed = true) {
        every { walletApplication } returns this@PlatformSyncIntegrityPassTest.walletApplication
    }
    private val contactRequestDao = mockk<DashPayContactRequestDao>(relaxed = true) {
        coEvery { loadToOthers(ownId) } returns listOf(sentRow)
        coEvery { loadFromOthers(ownId) } returns emptyList()
    }
    private val profileDao = mockk<DashPayProfileDao>(relaxed = true) {
        coEvery { loadByUserId(any()) } returns null
    }
    private val sdkProfileQueries = mockk<SdkProfileQueries>(relaxed = true) {
        coEvery { getProfileDocumentsOrNull(any()) } returns emptyList()
    }

    private fun service() = PlatformSynchronizationService(
        platform = platform,
        platformRepo = platformRepo,
        analytics = mockk(relaxed = true),
        config = mockk(relaxed = true),
        walletApplication = walletApplication,
        transactionMetadataProvider = mockk(relaxed = true),
        transactionMetadataChangeCacheDao = mockk(relaxed = true),
        transactionMetadataDocumentDao = mockk(relaxed = true),
        blockchainIdentityDataDao = mockk(relaxed = true) {
            coEvery { load() } returns identityData
        },
        dashPayProfileDao = profileDao,
        dashPayContactRequestDao = contactRequestDao,
        dashPayConfig = mockk(relaxed = true),
        giftCardDao = mockk(relaxed = true),
        invitationsDao = mockk(relaxed = true),
        usernameRequestDao = mockk(relaxed = true),
        usernameVoteDao = mockk(relaxed = true),
        identityConfig = mockk(relaxed = true),
        topUpRepository = mockk(relaxed = true),
        identityRepository = identityRepository,
        walletDataProvider = mockk(relaxed = true),
        walletSeam = mockk(relaxed = true),
        sdkProfileQueries = sdkProfileQueries,
        sdkUsernameQueries = mockk(relaxed = true),
        sdkIdentityVerifyQueries = mockk(relaxed = true),
        sdkWalletBinder = mockk(relaxed = true),
        nonInteractiveWalletUnlock = mockk(relaxed = true),
        l1ShadowSyncService = mockk(relaxed = true),
        shieldedBalanceService = mockk(relaxed = true),
        cutoverUiDataService = mockk(relaxed = true),
        sdkBlockchainStateService = mockk(relaxed = true),
        cutoverTxSeamService = mockk(relaxed = true),
        shieldedTransferExecutor = mockk(relaxed = true),
        contactRequestNotificationService = mockk(relaxed = true),
        dashPaySyncStatus = de.schildbach.wallet.service.DashPaySyncStatus()
    )

    @Test
    fun integrityPass_loadsTheContract_andRetriesTheSkippedContact() = runBlocking {
        // Contracts.get fills the app definition on success, like the real one.
        every { contracts.get(dashpayContractId) } answers {
            mockk<DataContract>().also { dashpayApp.contract = it }
        }
        assertFalse(PlatformSynchronizationService.isDashPayContractLoaded(legacyPlatform))

        withTimeout(30_000) { service().updateContactRequests(initialSync = false) }

        // The contract was loaded instead of the pass being skipped ...
        verify(exactly = 1) { contracts.get(dashpayContractId) }
        assertTrue(PlatformSynchronizationService.isDashPayContractLoaded(legacyPlatform))
        // ... the stored row was rebuilt and its contact's lookup retried ...
        verify(atLeast = 1) { documents.create(any(), Identifier.from(ownId), any()) }
        verify(exactly = 1) { platform.getContactIdentity(Identifier.from(contactId)) }
        // ... and the contact's missing profile was re-fetched on its own.
        coVerify(atLeast = 1) { sdkProfileQueries.getProfileDocumentsOrNull(listOf(Identifier.from(contactId))) }
    }

    @Test
    fun contractUnavailable_stillRepairsProfiles_andBuildsNoDocument() = runBlocking {
        // The proved getDataContract came back null: Contracts.get NPEs.
        every { contracts.get(dashpayContractId) } throws NullPointerException()

        withTimeout(30_000) { service().updateContactRequests(initialSync = false) }

        verify(exactly = 1) { contracts.get(dashpayContractId) }
        // The guard still holds: no document built without the contract.
        verify(exactly = 0) { documents.create(any(), any(), any()) }
        verify(exactly = 0) { platform.getContactIdentity(any<Identifier>()) }
        // Profile repair does not need the contract.
        coVerify(atLeast = 1) { sdkProfileQueries.getProfileDocumentsOrNull(listOf(Identifier.from(contactId))) }
    }

    @Test
    fun healthyContacts_needNoContractAndNoLookup() = runBlocking {
        every { wallet.hasReceivingKeyChain(any()) } returns true
        every { contracts.get(dashpayContractId) } answers {
            mockk<DataContract>().also { dashpayApp.contract = it }
        }

        withTimeout(30_000) { service().updateContactRequests(initialSync = false) }

        verify(exactly = 0) { contracts.get(any<Identifier>()) }
        verify(exactly = 0) { platform.getContactIdentity(any<Identifier>()) }
    }
}
