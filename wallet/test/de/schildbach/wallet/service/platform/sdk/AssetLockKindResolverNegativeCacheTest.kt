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

import de.schildbach.wallet.database.dao.InvitationsDao
import de.schildbach.wallet.database.dao.TopUpsDao
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.dashfoundation.dashsdk.persistence.DashDatabase
import org.dashfoundation.dashsdk.persistence.dao.AssetLockDao
import org.dashfoundation.dashsdk.persistence.dao.TransactionDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression coverage for the unshield → "Received" (green arrow) bug.
 *
 * A shielded→transparent unshield is an AssetUnlock recorded INCOMING with NO
 * `asset_locks` row, so [AssetLockKindResolver.kindFor] can only classify it as
 * [AssetLockKind.UNSHIELD] via the SDK `transactions` table (kind 7). That table
 * is filled asynchronously by the wallet-changeset callback AFTER broadcast, and
 * there is no authoring-time UNSHIELD seed (unlike the SHIELD spend). Previously a
 * probe that raced ahead of the ledger write negative-cached the transient
 * "not a known asset lock" null and pinned the row on "Received" until the 10-min
 * TTL lapsed. The fix: a null `transactionKindForDisplayTxid` is a PARTIAL verdict
 * (the tx is not in the SDK ledger yet) and must NOT be negative-cached, so the
 * next pass re-probes and the row flips to "Unshielded" the instant kind 7 lands.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssetLockKindResolverNegativeCacheTest {

    private val hex = "ab".repeat(32) // 64-char lowercase display txid

    private val transactionDao = mockk<TransactionDao>()
    private val assetLockDao = mockk<AssetLockDao> {
        coEvery { fundingTypeForTxid(any()) } returns null
    }
    private val db = mockk<DashDatabase> {
        every { transactionDao() } returns transactionDao
        every { assetLockDao() } returns assetLockDao
        // No shielded activity recorded → any kind-7 resolves to the
        // EXTERNAL (foreign-pool) unshield in these tests.
        every { shieldedDao() } returns mockk {
            coEvery { getAllActivity() } returns emptyList()
        }
    }
    private val sdkService = mockk<DashSdkService> {
        every { databaseOrNull() } returns db
    }
    private val blockchainIdentityConfig = mockk<BlockchainIdentityConfig> {
        coEvery { get(BlockchainIdentityConfig.ASSET_LOCK_TXID) } returns null
    }
    private val topUpsDao = mockk<TopUpsDao> {
        coEvery { getByTxId(any()) } returns null
    }
    private val invitationsDao = mockk<InvitationsDao> {
        coEvery { loadByUsername(any()) } returns null
    }

    private fun resolver() = AssetLockKindResolver(
        blockchainIdentityConfig,
        topUpsDao,
        invitationsDao,
        sdkService
    )

    @Test
    fun nullTransactionKind_isNotPinned_soLateUnshieldStillResolves() = runTest {
        val resolver = resolver()
        // Pass 1: the AssetUnlock is not in the SDK `transactions` table yet.
        coEvery { transactionDao.transactionKindForDisplayTxid(hex) } returns null
        assertNull(resolver.kindFor(hex))

        // Pass 2: the wallet-changeset callback has now recorded kind 7. Because
        // the transient null was NOT negative-cached, this pass re-probes and the
        // row resolves to an unshield kind (→ "Unshielded" re-stamp downstream).
        // With no shielded activity recorded here, ownership evidence is absent,
        // so it is the EXTERNAL (foreign-pool) variant.
        coEvery { transactionDao.transactionKindForDisplayTxid(hex) } returns 7
        assertEquals(AssetLockKind.UNSHIELD_EXTERNAL, resolver.kindFor(hex))

        // Proof the second pass actually re-probed the ledger (not served a pin).
        coVerify(exactly = 2) { transactionDao.transactionKindForDisplayTxid(hex) }
    }

    @Test
    fun recordedNonAssetLock_isStillNegativeCached_noReprobeRegression() = runTest {
        val resolver = resolver()
        // A genuine external receive IS upserted into the SDK `transactions`
        // table (a non-null Standard kind), so its "not an asset lock" verdict is
        // definitive and must still be negative-cached — the N+1 guard the cache
        // exists for.
        coEvery { transactionDao.transactionKindForDisplayTxid(hex) } returns 0
        assertNull(resolver.kindFor(hex))
        assertNull(resolver.kindFor(hex))

        // The second call was served from the negative cache: no re-probe.
        coVerify(exactly = 1) { transactionDao.transactionKindForDisplayTxid(hex) }
    }
}
