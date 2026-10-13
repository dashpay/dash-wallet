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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package de.schildbach.wallet.service.platform.sdk

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionInput
import org.bitcoinj.core.TransactionOutPoint
import org.bitcoinj.params.TestNet3Params
import org.dashfoundation.dashsdk.persistence.DashDatabase
import org.dashfoundation.dashsdk.persistence.entities.AccountEntity
import org.dashfoundation.dashsdk.persistence.entities.AssetLockEntity
import org.dashfoundation.dashsdk.persistence.entities.CoreAddressEntity
import org.dashfoundation.dashsdk.persistence.entities.TxoEntity
import org.dashfoundation.dashsdk.wallet.PlatformWalletManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoinJoinShieldFundingTest {
    @Test
    fun coinJoinPathsUseDedicatedDrainInsteadOfBip44Funding() = runBlocking {
        val manager = mockk<PlatformWalletManager>(relaxed = true)
        val service = mockk<DashSdkService>(relaxed = true)
        coEvery { service.walletManagerOrNull() } returns manager
        val source = DashSdkShieldedSource(service)
        val walletId = ByteArray(32)
        val recipient = ByteArray(43)

        for (path in listOf("m/9'/5'/4'/0'", "m/9'/1'/4'/0'")) {
            source.fundFromAssetLockFromAccount(walletId, recipient, 206741800L, path)
        }

        coVerify(exactly = 2) {
            manager.shieldedFundFromCoinJoinDrain(walletId, recipient, coinJoinAccountIndex = 0)
        }
        coVerify(exactly = 0) {
            manager.shieldedFundFromAssetLock(any(), any(), any(), any(), any())
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedFundingPathIsRejectedBeforeSdkStartup() = runBlocking {
        val service = mockk<DashSdkService>()
        DashSdkShieldedSource(service).fundFromAssetLockFromAccount(
            ByteArray(32), ByteArray(43), 1L, "m/44'/1'/0'"
        )
    }

    private val params = TestNet3Params.get()
    private val coinJoinAddress = "yTb47qEBpNmgXvYYsHEN4nh8yJwa5iC4Cs"
    private val bip44Address = "yXfXh3jFYHHxnJZVsXnPcktCENqPaAhcX1"

    private fun outpoint(seed: String) = TransactionOutPoint(params, 0, Sha256Hash.of(seed.toByteArray()))

    private fun txo(seed: String, address: String, accountId: Long?) =
        TxoEntity(outpoint(seed).bitcoinSerialize(), 0, 1_000_000, address, accountId = accountId)

    private fun lockTx(vararg seeds: String): Transaction = Transaction(params).apply {
        seeds.forEach { addInput(TransactionInput(params, this, byteArrayOf(), outpoint(it))) }
        addOutput(Coin.valueOf(900_000), Address.fromBase58(params, bip44Address))
    }

    @Test
    fun classifiesLockFundingByItsInputs() {
        val cj = txo("cj", coinJoinAddress, 7L)
        val bip44 = txo("bip44", bip44Address, 1L)
        val inputs = { seeds: List<String> -> seeds.map { outpoint(it).bitcoinSerialize() } }
        val addresses = setOf(coinJoinAddress)

        assertEquals(true, classifyCoinJoinFunding(inputs(listOf("cj")), listOf(cj), 7L, addresses))
        assertEquals(false, classifyCoinJoinFunding(inputs(listOf("bip44")), listOf(bip44), 7L, addresses))
        // A missing TXO row is UNKNOWN, never "BIP44" ...
        assertNull(classifyCoinJoinFunding(inputs(listOf("bip44", "gone")), listOf(bip44), 7L, addresses))
        // ... but one CoinJoin input decides it.
        assertEquals(true, classifyCoinJoinFunding(inputs(listOf("gone", "cj")), listOf(cj), 7L, addresses))
        // Address membership alone identifies a CoinJoin coin whose account pointer is unset.
        val unlinked = cj.copy(accountId = null)
        assertEquals(true, classifyCoinJoinFunding(inputs(listOf("cj")), listOf(unlinked), 7L, addresses))
        assertNull(classifyCoinJoinFunding(emptyList(), emptyList(), 7L, addresses))
    }

    @Test
    fun productionSourceTagsOnlyCoinJoinFundedLocksAsPendingDrains() = runBlocking {
        val walletId = ByteArray(32) { 1 }
        val db = mockk<DashDatabase>(relaxed = true)
        val service = mockk<DashSdkService>(relaxed = true)
        every { service.databaseOrNull() } returns db
        val cjTx = lockTx("cj")
        val bip44Tx = lockTx("bip44")
        fun lock(tx: Transaction, status: Int) = AssetLockEntity(
            "${tx.txId}:0", walletId, tx.bitcoinSerialize(), fundingTypeRaw = 5,
            identityIndexRaw = 0, amountDuffs = 900_000, statusRaw = status
        )
        every { db.assetLockDao().observeByWalletAndFundingType(walletId, 5) } returns
            flowOf(listOf(lock(cjTx, 2), lock(bip44Tx, 1), lock(lockTx("old"), 4)))
        coEvery { db.accountDao().getByKey(walletId, ACCOUNT_TYPE_TAG_COIN_JOIN, 0) } returns listOf(
            AccountEntity(id = 7, walletId = walletId, accountType = 1, accountIndex = 0, accountTypeName = "CoinJoin")
        )
        every { db.coreAddressDao().observeByAccount(7) } returns flowOf(
            listOf(CoreAddressEntity(coinJoinAddress, poolTypeTag = 0, addressIndex = 0, derivationPath = "", accountId = 7L))
        )
        val rows = listOf(txo("cj", coinJoinAddress, 7L), txo("bip44", bip44Address, 1L))
        coEvery { db.txoDao().getByOutpoints(any()) } answers {
            val wanted = firstArg<List<ByteArray>>()
            rows.filter { row -> wanted.any { it.contentEquals(row.outpoint) } }
        }

        val locks = DashSdkShieldedSource(service).walletShieldLocks(walletId)

        assertEquals(listOf(true, false, null), locks.map { it.coinJoinFunded })
        assertTrue(locks[0].pendingCoinJoinDrain)
        assertFalse(locks[1].pendingCoinJoinDrain)
        assertTrue(locks[1].resumable)
        assertFalse(locks[2].pendingCoinJoinDrain)
    }
}
