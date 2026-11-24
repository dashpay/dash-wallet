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
import org.junit.Assert.assertThrows
import org.junit.Test

class TopUpIdentityTargetTest {
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
    }

    private fun repository() = TopUpRepositoryImpl(
        mockk(relaxed = true), walletData, identityRepository, mockk(relaxed = true), topUpsDao,
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), verifier, mockk(relaxed = true)
    )

    private fun identity(id: String) = mockk<BlockchainIdentity>(relaxed = true) {
        every { uniqueIdString } returns id
    }

    @Test
    fun `tops up the identity being created while the repository has none`() = runTest {
        every { identityRepository.blockchainIdentity } returns null
        coEvery { topUpsDao.getByTxId(txId) } returns null
        val creating = identity("creating")

        repository().topUpIdentity(topUpTx, null, creating)

        coVerify { topUpsDao.insert(TopUp(txId, "creating")) }
        verify { creating.topUp(topUpTx, null, true, true) }
    }

    @Test
    fun `an explicit identity wins over the repository's`() = runTest {
        val loaded = identity("loaded")
        every { identityRepository.blockchainIdentity } returns loaded
        coEvery { topUpsDao.getByTxId(txId) } returns null
        val creating = identity("creating")

        repository().topUpIdentity(topUpTx, null, creating)

        coVerify { topUpsDao.insert(TopUp(txId, "creating")) }
        verify { creating.topUp(topUpTx, null, true, true) }
        verify(exactly = 0) { loaded.topUp(any(), any(), any(), any()) }
    }

    @Test
    fun `without an explicit identity it tops up the repository's`() = runTest {
        val loaded = identity("loaded")
        every { identityRepository.blockchainIdentity } returns loaded
        coEvery { topUpsDao.getByTxId(txId) } returns null

        repository().topUpIdentity(topUpTx, null)

        coVerify { topUpsDao.insert(TopUp(txId, "loaded")) }
        verify { loaded.topUp(topUpTx, null, true, true) }
    }

    @Test
    fun `with no identity at all it fails before recording a top-up`() {
        every { identityRepository.blockchainIdentity } returns null
        coEvery { topUpsDao.getByTxId(txId) } returns null

        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository().topUpIdentity(topUpTx, null) }
        }
        coVerify(exactly = 0) { topUpsDao.insert(any<TopUp>()) }
    }
}
