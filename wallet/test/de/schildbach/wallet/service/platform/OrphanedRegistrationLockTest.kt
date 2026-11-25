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
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.bitcoinj.core.Context
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionConfidence
import org.bitcoinj.evolution.AssetLockTransaction
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.authentication.AuthenticationGroupExtension
import org.bouncycastle.crypto.params.KeyParameter
import org.dashj.platform.dashpay.BlockchainIdentity
import org.dashj.platform.dapiclient.DapiClient
import org.dashj.platform.dapiclient.model.GetTransactionResponse
import org.dashj.platform.dashpay.IdentityStatus
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.dpp.identity.Identity
import org.dashj.platform.sdk.platform.Identities
import org.dashj.platform.sdk.platform.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Date
import java.util.concurrent.TimeUnit

class OrphanedRegistrationLockTest {
    private class FakeTopUpsDao : TopUpsDao {
        val rows = mutableMapOf<Sha256Hash, TopUp>()
        override suspend fun insert(topUp: TopUp) { rows[topUp.txId] = topUp }
        override suspend fun getByUserId(userId: String) = rows.values.filter { it.toUserId == userId }
        override suspend fun getByTxId(txId: Sha256Hash) = rows[txId]
        override fun observe(txId: Sha256Hash): Flow<TopUp?> = flowOf(rows[txId])
        override suspend fun getAll() = rows.values.toList()
        override suspend fun clear() = rows.clear()
        override suspend fun count() = rows.size
        override fun getUnused() = rows.values.filter { it.notUsed() }
    }

    private val ownId = hash("aa")
    private val context = Context(TestNet3Params.get())
    private val topUpsDao = FakeTopUpsDao()
    private val identityRepository = mockk<IdentityRepository>(relaxed = true)
    private val platformService = mockk<PlatformService>(relaxed = true)
    private val platformRepo = mockk<PlatformRepo>(relaxed = true)
    private val authExtension = mockk<AuthenticationGroupExtension>(relaxed = true)
    private val wallet = mockk<Wallet>(relaxed = true)
    private val walletData = mockk<WalletData>(relaxed = true)
    private val verifier = mockk<PendingDirectPaymentVerifier>()
    private val own = mockk<BlockchainIdentity>(relaxed = true)
    private val ownIdentity = mockk<Identity>(relaxed = true)
    private val identities = mockk<Identities>()
    private val dapiClient = mockk<DapiClient>()
    private val lockKey = mockk<ECKey>()

    init {
        every { platformRepo.platform } returns platformService
        every { platformService.client } returns dapiClient
        every { dapiClient.getTransactionKotlin(any()) } returns null
        every { wallet.context } returns context
        every { wallet.getKeyChainExtension(AuthenticationGroupExtension.EXTENSION_ID) } returns authExtension
        every { walletData.wallet } returns wallet
        coEvery { verifier.awaitRestored() } just Runs
        every { platformService.getIdentity(any<Identifier>()) } returns null
        every { own.uniqueId } returns ownId
        every { own.uniqueIdString } returns "own"
        every { own.registrationStatus } returns IdentityStatus.REGISTERED
        every { own.identity } returns ownIdentity
        every { own.platform } returns mockk<Platform>().also { every { it.identities } returns identities }
        every { identities.topUp(any(), any(), any<Transaction>(), any<Long>(), any()) } returns 3_000_000_000L
        every { lockKey.isEncrypted } returns false
        every { identityRepository.blockchainIdentity } returns own
    }

    private fun repository() = TopUpRepositoryImpl(
        mockk(relaxed = true), walletData, identityRepository, platformRepo, topUpsDao,
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), verifier, mockk(relaxed = true)
    )

    private fun hash(hex: String) = Sha256Hash.wrap(hex.repeat(32))

    private fun onCore(hex: String, height: Int, chainLocked: Boolean = true) {
        every { dapiClient.getTransactionKotlin(hex.repeat(32)) } returns
            GetTransactionResponse(ByteArray(0), hash("ee"), height, 6, false, chainLocked)
    }

    /** A dashj registration lock that Platform's Core reports chain locked at [minedAt], unless null. */
    private fun lock(
        hex: String,
        identityId: Sha256Hash = hash(hex),
        minedAt: Int? = MINED_AT,
        ageMs: Long = TimeUnit.DAYS.toMillis(1),
        updateTime: Date = Date(System.currentTimeMillis() - ageMs),
        confidence: TransactionConfidence = TransactionConfidence(hash(hex))
    ): AssetLockTransaction {
        val tx = mockk<AssetLockTransaction>(relaxed = true)
        every { tx.txId } returns hash(hex)
        every { tx.identityId } returns identityId
        every { tx.assetLockPublicKey } returns lockKey
        every { tx.getConfidence(any<Context>()) } returns confidence
        val walletCopy = mockk<Transaction>(relaxed = true)
        every { walletCopy.updateTime } returns updateTime
        every { wallet.getTransaction(hash(hex)) } returns walletCopy
        minedAt?.let { onCore(hex, it) }
        return tx
    }

    private fun fundingLocks(vararg locks: AssetLockTransaction) {
        every { authExtension.identityFundingTransactions } returns locks.toList()
    }

    private fun toppedUp(lock: AssetLockTransaction, height: Int = MINED_AT) =
        verify { identities.topUp(ownIdentity, 0, lock, height.toLong(), lockKey) }

    private fun assertNotToppedUp() =
        verify(exactly = 0) { identities.topUp(any(), any(), any<Transaction>(), any<Long>(), any()) }

    private fun rejectAsUsed(lock: AssetLockTransaction) {
        every { identities.topUp(any(), any(), lock, any<Long>(), any()) } throws IllegalStateException(
            "Asset lock transaction ${lock.txId} output 0 already completely used"
        )
    }

    @Test
    fun `credits a mined registration lock that never registered an identity`() = runTest {
        val orphan = lock("01")
        fundingLocks(orphan)

        repository().checkTopUps(null)

        toppedUp(orphan)
        verify(exactly = 0) { own.topUp(any(), any(), any(), any()) }
        val row = topUpsDao.rows.getValue(hash("01"))
        assertEquals("own", row.toUserId)
        assertEquals(true, row.used())
    }

    @Test
    fun `credits a lock still pending in dashj at Core's height and leaves the wallet's copy as it was`() = runTest {
        val confidence = TransactionConfidence(hash("11"))
        confidence.confidenceType = TransactionConfidence.ConfidenceType.PENDING
        val orphan = lock("11", minedAt = 1234, confidence = confidence)
        fundingLocks(orphan)

        repository().checkTopUps(null)

        toppedUp(orphan, 1234)
        assertEquals(TransactionConfidence.ConfidenceType.PENDING, confidence.confidenceType)
        assertEquals(true, topUpsDao.rows.getValue(hash("11")).used())
    }

    @Test
    fun `uses Core's height over the one dashj saved`() = runTest {
        val confidence = TransactionConfidence(hash("12"))
        confidence.appearedAtChainHeight = 900
        val orphan = lock("12", minedAt = 1000, confidence = confidence)
        fundingLocks(orphan)

        repository().checkTopUps(null)

        toppedUp(orphan, 1000)
        assertEquals(900, confidence.appearedAtChainHeight)
    }

    @Test
    fun `a rejected height does not stop a later launch from using the corrected one`() = runTest {
        val orphan = lock("14", minedAt = 999)
        fundingLocks(orphan)
        every { identities.topUp(any(), any(), orphan, 999L, any()) } throws
            IllegalStateException("Core chain locked height is below the proof height")

        repository().checkTopUps(null)
        assertNull(topUpsDao.rows[hash("14")])

        onCore("14", 1000)
        repository().checkTopUps(null)

        toppedUp(orphan, 1000)
        assertEquals(true, topUpsDao.rows.getValue(hash("14")).used())
    }

    @Test
    fun `decrypts the lock key with the wallet key`() = runTest {
        val orphan = lock("15")
        fundingLocks(orphan)
        val aesKey = KeyParameter(ByteArray(32))
        val decrypted = mockk<ECKey>()
        every { lockKey.isEncrypted } returns true
        every { lockKey.decrypt(aesKey) } returns decrypted

        repository().checkTopUps(aesKey)

        verify { identities.topUp(ownIdentity, 0, orphan, MINED_AT.toLong(), decrypted) }
    }

    @Test
    fun `skips a lock whose identity exists`() = runTest {
        fundingLocks(lock("02"))
        every { platformService.getIdentity(Identifier.from(hash("02"))) } returns mockk(relaxed = true)

        repository().checkTopUps(null)

        assertNotToppedUp()
        assertNull(topUpsDao.rows[hash("02")])
    }

    @Test
    fun `skips the lock that registered the wallet's identity`() = runTest {
        fundingLocks(lock("03", identityId = ownId))

        repository().checkTopUps(null)

        verify(exactly = 0) { platformService.getIdentity(any<Identifier>()) }
        assertNotToppedUp()
    }

    @Test
    fun `skips a lock already credited`() = runTest {
        fundingLocks(lock("04"))
        topUpsDao.insert(TopUp(hash("04"), "own", creditedAt = 1L))

        repository().checkTopUps(null)

        verify(exactly = 0) { platformService.getIdentity(any<Identifier>()) }
        assertNotToppedUp()
    }

    @Test
    fun `skips a lock Core has not seen mined`() = runTest {
        fundingLocks(lock("05", minedAt = 0))

        repository().checkTopUps(null)

        verify { dapiClient.getTransactionKotlin("05".repeat(32)) }
        assertNotToppedUp()
        assertNull(topUpsDao.rows[hash("05")])
    }

    @Test
    fun `skips a lock Core does not know`() = runTest {
        fundingLocks(lock("10", minedAt = null))

        repository().checkTopUps(null)

        assertNotToppedUp()
    }

    @Test
    fun `skips a mined lock that is not chain locked yet`() = runTest {
        fundingLocks(lock("16", minedAt = null))
        onCore("16", 1000, chainLocked = false)

        repository().checkTopUps(null)

        assertNotToppedUp()
    }

    @Test
    fun `skips a lock when Core cannot be reached`() = runTest {
        fundingLocks(lock("13", minedAt = null))
        every { dapiClient.getTransactionKotlin("13".repeat(32)) } throws IllegalStateException("dapi down")

        repository().checkTopUps(null)

        assertNotToppedUp()
    }

    @Test
    fun `skips a lock created within the last hour`() = runTest {
        fundingLocks(lock("06", ageMs = TimeUnit.MINUTES.toMillis(10)))

        repository().checkTopUps(null)

        verify(exactly = 0) { platformService.getIdentity(any<Identifier>()) }
        assertNotToppedUp()
    }

    @Test
    fun `skips a lock the wallet has no update time for`() = runTest {
        fundingLocks(lock("0e", updateTime = Date(0)))

        repository().checkTopUps(null)

        verify(exactly = 0) { platformService.getIdentity(any<Identifier>()) }
        assertNotToppedUp()
    }

    @Test
    fun `does nothing until the wallet's identity is registered`() = runTest {
        fundingLocks(lock("07"))
        every { own.registrationStatus } returns IdentityStatus.NOT_REGISTERED

        repository().checkTopUps(null)

        verify(exactly = 0) { platformService.getIdentity(any<Identifier>()) }
        assertNotToppedUp()
    }

    @Test
    fun `does nothing without the wallet identity's Platform record`() = runTest {
        fundingLocks(lock("08"))
        every { own.identity } returns null

        repository().checkTopUps(null)

        verify(exactly = 0) { platformService.getIdentity(any<Identifier>()) }
        assertNotToppedUp()
    }

    @Test
    fun `records the wallet identity when crediting a lock first recorded for another identity`() = runTest {
        val orphan = lock("09")
        fundingLocks(orphan)
        topUpsDao.insert(TopUp(hash("09"), "adopted"))

        repository().checkTopUps(null)

        toppedUp(orphan)
        val row = topUpsDao.rows.getValue(hash("09"))
        assertEquals("own", row.toUserId)
        assertEquals(true, row.used())
    }

    @Test
    fun `a lock Platform reports as used is not recorded as credited`() = runTest {
        val spent = lock("0a")
        fundingLocks(spent)
        topUpsDao.insert(TopUp(hash("0a"), "adopted"))
        rejectAsUsed(spent)

        repository().checkTopUps(null)

        val row = topUpsDao.rows.getValue(hash("0a"))
        assertEquals("adopted", row.toUserId)
        assertEquals(false, row.used())
    }

    @Test
    fun `a lock Platform reports as used leaves no top-up record when there was none`() = runTest {
        val spent = lock("0f")
        fundingLocks(spent)
        rejectAsUsed(spent)

        repository().checkTopUps(null)

        assertNull(topUpsDao.rows[hash("0f")])
    }

    @Test
    fun `a failure on one lock does not stop the others`() = runTest {
        val broken = lock("0b")
        val orphan = lock("0c")
        fundingLocks(broken, orphan)
        every { platformService.getIdentity(Identifier.from(hash("0b"))) } throws IllegalStateException("dapi down")

        repository().checkTopUps(null)

        verify(exactly = 0) { identities.topUp(any(), any(), broken, any<Long>(), any()) }
        toppedUp(orphan)
    }

    @Test
    fun `cancellation is not swallowed`() {
        fundingLocks(lock("0d"))
        every { platformService.getIdentity(any<Identifier>()) } throws CancellationException("stopped")

        assertThrows(CancellationException::class.java) {
            runBlocking { repository().checkTopUps(null) }
        }
    }

    private companion object {
        const val MINED_AT = 1598126
    }
}
