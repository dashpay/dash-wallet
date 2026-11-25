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

import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.database.dao.TopUpsDao
import de.schildbach.wallet.database.entity.TopUp
import de.schildbach.wallet.payments.PendingDirectPaymentVerifier
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.bitcoinj.core.Context
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.TransactionConfidence
import org.bitcoinj.evolution.AssetLockTransaction
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.wallet.Wallet
import org.dashj.platform.dashpay.BlockchainIdentity
import org.dashj.platform.dashpay.IdentityStatus
import org.junit.Assert.assertThrows
import org.junit.Test

class TopUpIdentityGuardTest {
    private val txId = Sha256Hash.wrap("11".repeat(32))
    private val topUpsDao = mockk<TopUpsDao>(relaxed = true)
    private val identityRepository = mockk<IdentityRepository>(relaxed = true)
    private val context = Context(TestNet3Params.get())
    private val confidence = mockk<TransactionConfidence>(relaxed = true)
    private val topUpTx = mockk<AssetLockTransaction>(relaxed = true)
    private val wallet = mockk<Wallet>(relaxed = true)
    private val walletData = mockk<WalletData>(relaxed = true)
    private val verifier = mockk<PendingDirectPaymentVerifier> { coEvery { awaitRestored() } just Runs }

    init {
        every { confidence.confidenceType } returns TransactionConfidence.ConfidenceType.BUILDING
        every { confidence.isChainLocked } returns true
        every { topUpTx.txId } returns txId
        every { topUpTx.getConfidence(any<Context>()) } returns confidence
        every { wallet.context } returns context
        every { walletData.wallet } returns wallet
        coEvery { topUpsDao.getByTxId(txId) } returns null
    }

    private fun repository() = TopUpRepositoryImpl(
        mockk(relaxed = true), walletData, identityRepository, mockk(relaxed = true), topUpsDao,
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), verifier, mockk(relaxed = true)
    )

    private fun identity(id: String, status: IdentityStatus = IdentityStatus.REGISTERED) =
        mockk<BlockchainIdentity>(relaxed = true) {
            every { uniqueIdString } returns id
            every { registrationStatus } returns status
        }

    private fun assertRefused(identity: BlockchainIdentity?) {
        every { identityRepository.blockchainIdentity } returns identity
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository().topUpIdentity(topUpTx, null) }
        }
        coVerify(exactly = 0) { topUpsDao.insert(any<TopUp>()) }
        identity?.let { verify(exactly = 0) { it.topUp(any(), any(), any(), any()) } }
    }

    @Test
    fun `tops up and records the registered identity`() = runTest {
        val loaded = identity("loaded")
        every { identityRepository.blockchainIdentity } returns loaded

        repository().topUpIdentity(topUpTx, null)

        coVerify { topUpsDao.insert(TopUp(txId, "loaded")) }
        verify { loaded.topUp(topUpTx, null, true, true) }
    }

    @Test
    fun `a top-up Platform reports as used is recorded as credited`() = runTest {
        val loaded = identity("loaded")
        every { identityRepository.blockchainIdentity } returns loaded
        every { loaded.topUp(topUpTx, any(), any(), any()) } throws IllegalStateException(
            "Asset lock transaction ${"11".repeat(32)} output 0 already completely used"
        )

        repository().topUpIdentity(topUpTx, null)

        coVerify { topUpsDao.insert(match<TopUp> { it.txId == txId && it.used() }) }
    }

    @Test
    fun `with no identity it fails before recording a top-up`() = assertRefused(null)

    @Test
    fun `an identity that is not registered is not recorded or topped up`() =
        assertRefused(identity("funding-lock-id", IdentityStatus.UNKNOWN))

    @Test
    fun `an identity without an id is not recorded or topped up`() =
        assertRefused(
            mockk<BlockchainIdentity>(relaxed = true) {
                every { uniqueIdString } throws UninitializedPropertyAccessException("uniqueId")
                every { registrationStatus } returns IdentityStatus.REGISTERED
            }
        )
}
