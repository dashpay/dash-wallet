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

package de.schildbach.wallet.service.platform.sdk

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import de.schildbach.wallet.Constants
import de.schildbach.wallet.database.dao.InvitationsDao
import de.schildbach.wallet.database.dao.TopUpsDao
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Context
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.evolution.AssetLockTransaction
import org.bitcoinj.script.ScriptBuilder
import org.dashfoundation.dashsdk.persistence.DashDatabase
import org.dashfoundation.dashsdk.persistence.dao.AssetLockDao
import org.dashfoundation.dashsdk.persistence.dao.TransactionDao
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The restore fallback's credit-account probe must read only the ACTIVE SDK
 * wallet's accounts: `core_addresses` has no walletId, and the SDK store can
 * hold an orphan wallet from an earlier restore. When the active wallet
 * cannot be named, the probe fails closed and the verdict is not cached.
 */
class AssetLockKindResolverWalletScopeTest {

    private val params = Constants.NETWORK_PARAMETERS.also { Context.propagate(Context(it)) }
    private val walletIdHex = "cd".repeat(32)

    private val lockBytes = AssetLockTransaction(params, ECKey(), Coin.COIN).apply {
        addInput(Sha256Hash.ZERO_HASH, 0, ScriptBuilder().build())
    }.bitcoinSerialize()
    private val hex = Sha256Hash.twiceOf(lockBytes).toString()

    private val queries = ArrayList<SupportSQLiteQuery>()
    private val readable = mockk<SupportSQLiteDatabase> {
        every { query(any<SupportSQLiteQuery>()) } answers {
            val q = firstArg<SupportSQLiteQuery>()
            queries += q
            if (q.sql.startsWith("SELECT transactionData")) {
                cursorOf(listOf(lockBytes)) { c, row -> every { c.getBlob(0) } returns row }
            } else {
                // The identity-registration account (AccountTypeTagFFI 2).
                cursorOf(listOf(2)) { c, row -> every { c.getInt(0) } returns row }
            }
        }
    }
    private val transactionDao = mockk<TransactionDao> {
        coEvery { transactionKindForDisplayTxid(any()) } returns TX_TYPE_KIND_ASSET_LOCK
    }
    private val db = mockk<DashDatabase> {
        every { transactionDao() } returns transactionDao
        every { assetLockDao() } returns mockk<AssetLockDao> {
            coEvery { fundingTypeForTxid(any()) } returns null
        }
        every { openHelper } returns mockk<SupportSQLiteOpenHelper> {
            every { readableDatabase } returns readable
        }
    }
    private val sdkService = mockk<DashSdkService> {
        every { databaseOrNull() } returns db
    }

    private fun resolver() = AssetLockKindResolver(
        mockk<BlockchainIdentityConfig> {
            coEvery { get(BlockchainIdentityConfig.ASSET_LOCK_TXID) } returns null
        },
        mockk<TopUpsDao> { coEvery { getByTxId(any()) } returns null },
        mockk<InvitationsDao> { coEvery { loadByUsername(any()) } returns null },
        sdkService
    )

    @Test
    fun creditAccountLookup_isScopedToTheActiveWallet() = runTest {
        every { sdkService.loadedWalletIds() } returns setOf(walletIdHex)

        assertEquals(AssetLockKind.UPGRADE, resolver().kindFor(hex))

        val accountQuery = queries.single { it.sql.contains("core_addresses") }
        assertTrue(accountQuery.sql, accountQuery.sql.contains("a.walletId = ?"))
        val binds = HashMap<Int, Any?>()
        val program = mockk<SupportSQLiteProgram>(relaxed = true) {
            every { bindBlob(any(), any()) } answers { binds[firstArg()] = secondArg<ByteArray>() }
            every { bindString(any(), any()) } answers { binds[firstArg()] = secondArg<String>() }
        }
        accountQuery.bindTo(program)
        assertArrayEquals(walletIdFromHex(walletIdHex), binds[1] as ByteArray)
    }

    @Test
    fun noLoadedWallet_failsClosedAndIsNotNegativeCached() = runTest {
        every { sdkService.loadedWalletIds() } returns emptySet()
        assertUnknownWalletVerdictIsPartial()
    }

    @Test
    fun ambiguousWallets_failClosedAndAreNotNegativeCached() = runTest {
        every { sdkService.loadedWalletIds() } returns setOf(walletIdHex, "ef".repeat(32))
        assertUnknownWalletVerdictIsPartial()
    }

    private suspend fun assertUnknownWalletVerdictIsPartial() {
        val resolver = resolver()
        assertNull(resolver.kindFor(hex))
        assertNull(resolver.kindFor(hex))
        // Re-probed on the second pass: the skipped probe was not pinned.
        coVerify(exactly = 2) { transactionDao.transactionKindForDisplayTxid(hex) }
        // And no unscoped account read ran.
        verify(exactly = 0) { readable.query(any<SupportSQLiteQuery>()) }
    }

    private fun <T> cursorOf(rows: List<T>, stubRow: (Cursor, T) -> Unit): Cursor {
        var index = -1
        val cursor = mockk<Cursor>(relaxed = true)
        every { cursor.moveToFirst() } answers { index = 0; rows.isNotEmpty() }
        every { cursor.moveToNext() } answers { index++; index < rows.size }
        every { cursor.isNull(0) } returns false
        rows.firstOrNull()?.let { stubRow(cursor, it) }
        return cursor
    }
}
