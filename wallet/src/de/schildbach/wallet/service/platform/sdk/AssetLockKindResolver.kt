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
import kotlinx.coroutines.CancellationException
import org.bitcoinj.core.Sha256Hash
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Platform-funding role of an L1 asset-lock transaction the wallet
 * authored. Post-cutover these are recorded by the SDK as INTERNAL moves
 * (they spend the wallet's own transparent UTXOs into an asset lock), so
 * without this classification the home-screen list and detail sheet
 * mislabel them "Internal"/"mixing" instead of the Platform action they
 * funded.
 */
enum class AssetLockKind { UPGRADE, TOPUP, INVITE, SHIELD, UNSHIELD }

/**
 * SDK `transactions.transactionKind` value for an AssetUnlock — the
 * unshield/withdraw self-move. It has NO `asset_locks` row and the SDK
 * records it as an INCOMING transfer, so it is classified from the
 * transactions table rather than the funding-type table.
 */
private const val ASSET_UNLOCK_TRANSACTION_KIND = 7

/**
 * SDK `asset_locks.fundingType` values (see the DAO seam doc): the durable
 * classification the wallet falls back to when the app-side records did not
 * capture the funding txid (e.g. after a reinstall/restore).
 */
private const val FUNDING_TYPE_UPGRADE = 0
private const val FUNDING_TYPE_TOPUP_A = 1
private const val FUNDING_TYPE_TOPUP_B = 2
private const val FUNDING_TYPE_INVITATION = 3
private const val FUNDING_TYPE_SHIELD = 5

/**
 * Convert a 32-byte WIRE-order txid (e.g. [org.dashfoundation.dashsdk.wallet.TrackedAssetLock.outpointTxid])
 * to DISPLAY-order (byte-reversed) lowercase hex — the `Sha256Hash.toString()`
 * / `tx_display_cache` rowId / `ASSET_LOCK_TXID` convention the resolver keys on.
 */
internal fun displayHexOf(wireTxid: ByteArray): String =
    wireTxid.reversedArray().joinToString("") { "%02x".format(it) }

/**
 * Classifies an L1 txid as a Platform-funding asset lock the app-side
 * records already know about — the SDK's neutral L1 view cannot tell an
 * identity-funding / top-up / invite asset lock apart from a plain internal
 * move, but the wallet persisted the funding txid when it authored the spend:
 * - identity funding → [BlockchainIdentityConfig.ASSET_LOCK_TXID] (the
 *   DataStore key, stored as `Sha256Hash.toString()` = lowercase display hex);
 * - top-up → a [TopUp] row keyed by the top-up asset-lock txid;
 * - invite → an [Invitation] row carrying the funding txid.
 *
 * Each probe is a fast Room / DataStore read; a null return means "not a
 * known asset lock" (the row stays a plain internal move). Fails closed to
 * null on any read error so a lookup failure can never mislabel a row.
 */
@Singleton
class AssetLockKindResolver @Inject constructor(
    private val blockchainIdentityConfig: BlockchainIdentityConfig,
    private val topUpsDao: TopUpsDao,
    private val invitationsDao: InvitationsDao,
    /**
     * The SDK lifecycle owner — the ONLY handle the resolver has on the SDK
     * Room DB ([DashSdkService.databaseOrNull]), the same accessor
     * [DashSdkCutoverUiSource] reads. Used read-only for the durable
     * asset-lock/transaction-kind probes (`assetLockDao()` / `transactionDao()`).
     * `databaseOrNull()` is a non-blocking snapshot: null before the SDK has
     * started, in which case the SDK probes are skipped and only the app-side
     * records classify the row (fail-closed to the pre-SDK behaviour).
     */
    private val sdkService: DashSdkService
) {
    /**
     * Process-lifetime, in-memory seed of the asset-lock kind, keyed by the
     * DISPLAY-order (byte-reversed) lowercase txid hex. The authoring paths
     * ([SdkTransparentUsernameCreation], [SdkTransparentTopUp]) call [seed] the
     * instant the asset-lock txid becomes known — synchronously, in the same
     * turn that inserts the tx into the engine feed — so the FIRST
     * classification of a freshly created asset lock already carries the right
     * kind. Without it the DataStore/Room persist ([BlockchainIdentityConfig.
     * ASSET_LOCK_TXID] / [TopUpsDao]) races the feed: the first insert sees no
     * record and classifies the row `sent_internally` ("Internal"), then a
     * later pass re-labels it ("Upgrade") — a visible flip. Checked BEFORE the
     * DataStore/Room probes; never evicted (a few 32-byte keys per process).
     */
    private val seeded = ConcurrentHashMap<String, AssetLockKind>()

    /**
     * Seed the [kind] for [displayHex] (DISPLAY-order txid hex, any case) so
     * the very first [kindFor] on it returns [kind] without waiting for the
     * DataStore/Room persist to land. Idempotent; keyed lowercase to match
     * [kindFor]'s normalization.
     */
    fun seed(displayHex: String, kind: AssetLockKind) {
        seeded[displayHex.lowercase()] = kind
    }

    /**
     * @param txDisplayHex DISPLAY-order (byte-reversed) txid hex — the same
     *   convention `tx_display_cache` rowIds and `Sha256Hash.toString()` use.
     */
    suspend fun kindFor(txDisplayHex: String): AssetLockKind? {
        val hex = txDisplayHex.lowercase()
        if (hex.length != 64 || !hex.all { it.isDigit() || it in 'a'..'f' }) return null
        // In-memory seed first — the authoring path recorded the kind
        // synchronously, so this beats the DataStore/Room persist race that
        // otherwise mislabels the first insert "Internal".
        seeded[hex]?.let { return it }
        return try {
            // The SDK Room DB snapshot (null until the SDK has started) — the
            // durable transaction-kind / funding-type probes read from it.
            val db = sdkService.databaseOrNull()

            // UNSHIELD FIRST — the AssetUnlock (withdraw/unshield) has NO
            // asset_locks row and the SDK records it as an INCOMING transfer,
            // so neither the app-side records nor fundingTypeForTxid below can
            // see it. It is classified only from the transactions table.
            if (db?.transactionDao()?.transactionKindForDisplayTxid(hex) == ASSET_UNLOCK_TRANSACTION_KIND) {
                return AssetLockKind.UNSHIELD
            }

            // UPGRADE — the identity-funding asset lock. Stored form is
            // Sha256Hash.toString() (lowercase display hex), so compare directly.
            val fundingTxid = blockchainIdentityConfig.get(BlockchainIdentityConfig.ASSET_LOCK_TXID)
            if (fundingTxid != null && fundingTxid.lowercase() == hex) {
                return AssetLockKind.UPGRADE
            }
            val sha = Sha256Hash.wrap(hex)
            if (topUpsDao.getByTxId(sha) != null) return AssetLockKind.TOPUP
            if (invitationsDao.loadByUsername(sha) != null) return AssetLockKind.INVITE

            // Durable fallback — the SDK's own asset_locks funding type, for a
            // lock the app-side records never captured (e.g. after a restore).
            when (db?.assetLockDao()?.fundingTypeForTxid(hex)) {
                FUNDING_TYPE_SHIELD -> AssetLockKind.SHIELD
                FUNDING_TYPE_INVITATION -> AssetLockKind.INVITE
                FUNDING_TYPE_TOPUP_A, FUNDING_TYPE_TOPUP_B -> AssetLockKind.TOPUP
                FUNDING_TYPE_UPGRADE -> AssetLockKind.UPGRADE
                else -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log.warn("asset-lock kind lookup failed for {}; treating as a plain internal move", hex, t)
            null
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(AssetLockKindResolver::class.java)
    }
}
