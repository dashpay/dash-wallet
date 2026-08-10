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

import de.schildbach.wallet.service.platform.sdk.SdkDashPayWrites
import de.schildbach.wallet.service.platform.sdk.SdkWriteResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.bitcoinj.core.Base58
import org.bouncycastle.crypto.params.KeyParameter
import org.dashj.platform.dashpay.BlockchainIdentity
import org.dashj.platform.dashpay.ContactRequests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The post-send reconcile contract of the Kotlin-SDK contact-request path
 * ([PlatformDocumentBroadcastService.sendContactRequest], Broadcast branch):
 * once the SDK confirms the broadcast, the operation HAS SUCCEEDED, and the
 * reconcile fetch that follows must never fail or stall it.
 *
 * Observed live (S21, 2026-08-09): the legacy CBOR identity-cache rejected the
 * recipient's contract, the uncapped watch ground through fetch retries and
 * quorum searches for 4+ minutes with the user on a spinner, and the old code
 * then THREW for a send that was already on Platform. The fix caps the watch
 * and reports success-with-deferred-reconcile: the contact sync pass performs
 * the same bookkeeping from the fetched document moments later.
 */
class PlatformBroadcastReconcileTest {

    private val ownId = Base58.encode(ByteArray(32) { 3 })
    private val contactId = Base58.encode(ByteArray(32) { 7 })

    private val blockchainIdentity = mockk<BlockchainIdentity>(relaxed = true) {
        every { uniqueIdString } returns ownId
    }
    private val identityRepository = mockk<IdentityRepository>(relaxed = true) {
        every { blockchainIdentity } returns this@PlatformBroadcastReconcileTest.blockchainIdentity
    }
    private val contactRequests = mockk<ContactRequests>()
    private val platform = mockk<PlatformService>(relaxed = true) {
        every { contactRequests } returns this@PlatformBroadcastReconcileTest.contactRequests
        every { getContactIdentity(any()) } returns mockk(relaxed = true)
    }
    private val platformSyncService = mockk<PlatformSyncService>(relaxed = true)
    private val sdkDashPayWrites = mockk<SdkDashPayWrites>(relaxed = true) {
        coEvery { sendContactRequest(any(), any()) } returns SdkWriteResult.Broadcast(Unit)
    }

    private fun service() = PlatformDocumentBroadcastService(
        dashSystemService = mockk(relaxed = true),
        platform = platform,
        identityRepository = identityRepository,
        platformRepo = mockk(relaxed = true),
        analytics = mockk(relaxed = true),
        walletDataProvider = mockk(relaxed = true),
        platformSyncService = platformSyncService,
        sdkDashPayWrites = sdkDashPayWrites,
        sdkIdentityVerifyWrites = mockk(relaxed = true),
        sdkWalletBinder = mockk(relaxed = true),
        sdkMasternodeQueries = mockk(relaxed = true)
    )

    @Test
    fun reconcileFetchFailure_isNonFatal_andDefersToContactSync() = runTest {
        // The exact live failure shape: the fetch path throws (legacy CBOR
        // cache rejection) after the SDK already confirmed the broadcast.
        coEvery {
            contactRequests.watchContactRequest(any(), any(), any(), any(), any())
        } throws RuntimeException("No converter for ContractBoundKey")

        val result = service().sendContactRequest(contactId, KeyParameter(ByteArray(32)))

        // Success is reported with the ids the caller consumes...
        assertEquals(ownId, result.userId)
        assertEquals(contactId, result.toUserId)
        // ...as a provisional row: no document-derived fields.
        assertEquals(0, result.encryptedPublicKey.size)
        // And the real bookkeeping was handed to the contact sync pass.
        verify(exactly = 1) { platformSyncService.requestContactUpdate() }
    }

    @Test
    fun reconcileFetchHang_isCapped_andDefersToContactSync() = runTest {
        // A watch that never returns: the cap must cut it loose instead of
        // holding the caller (previously a Worker driving a UI spinner).
        coEvery {
            contactRequests.watchContactRequest(any(), any(), any(), any(), any())
        } coAnswers { awaitCancellation() }

        val result = service().sendContactRequest(contactId, KeyParameter(ByteArray(32)))

        assertEquals(ownId, result.userId)
        assertEquals(contactId, result.toUserId)
        assertTrue(result.timestamp > 0)
        verify(exactly = 1) { platformSyncService.requestContactUpdate() }
    }
}
