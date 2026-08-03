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

/** Table for the allocation-free hex encoders below (lowercase, the app-wide convention). */
private val HEX_DIGITS = "0123456789abcdef".toCharArray()

/**
 * Allocation-lean lowercase hex of [bytes] in WIRE order — a table-driven
 * single pass replacing the former per-byte `"%02x".format(it)` lambda
 * (which allocated a Formatter + String per byte; measured as a top cost
 * of the 10s parity probe's full-table hex keying on large wallets).
 */
internal fun wireHexOf(bytes: ByteArray): String {
    val out = CharArray(bytes.size * 2)
    var i = 0
    for (b in bytes) {
        val v = b.toInt() and 0xff
        out[i++] = HEX_DIGITS[v ushr 4]
        out[i++] = HEX_DIGITS[v and 0x0f]
    }
    return String(out)
}

/**
 * Convert a 32-byte WIRE-order txid (e.g. [org.dashfoundation.dashsdk.wallet.TrackedAssetLock.outpointTxid])
 * to DISPLAY-order (byte-reversed) lowercase hex — the `Sha256Hash.toString()`
 * / `tx_display_cache` rowId / `ASSET_LOCK_TXID` convention the resolver keys on.
 * Table-driven reverse iteration: no `reversedArray()` copy, no per-byte format.
 */
internal fun displayHexOf(wireTxid: ByteArray): String {
    val out = CharArray(wireTxid.size * 2)
    var i = 0
    for (j in wireTxid.indices.reversed()) {
        val v = wireTxid[j].toInt() and 0xff
        out[i++] = HEX_DIGITS[v ushr 4]
        out[i++] = HEX_DIGITS[v and 0x0f]
    }
    return String(out)
}

/**
 * Parse lowercase/uppercase hex into bytes, or null when malformed. The inverse
 * of [wireHexOf]; callers reverse for display→wire conversion.
 */
internal fun hexToBytesOrNull(hex: String): ByteArray? {
    if (hex.length % 2 != 0) return null
    val out = ByteArray(hex.length / 2)
    for (i in out.indices) {
        val hi = Character.digit(hex[2 * i], 16)
        val lo = Character.digit(hex[2 * i + 1], 16)
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

/**
 * Bounded, TTL'd NEGATIVE resolution cache keyed by display txid hex — the
 * fix for the resolver N+1 on the 60s display-sync tick: a row that resolved
 * to "no match" was re-probed (Room/DataStore reads) on EVERY tick, so a
 * large wallet paid thousands of queries a minute forever. A negative entry
 * suppresses the re-probe until it is explicitly removed ([remove]/[clear] —
 * the authoring/seed and contact re-resolution busting paths) or its TTL
 * lapses (the conservative belt: even a missed busting signal self-heals in
 * [ttlMs], so a post-restore state can never be pinned wrong permanently).
 * Access-ordered LRU capped at [maxSize]. Thread-safe.
 */
internal class NegativeTxidCache(
    private val maxSize: Int = DEFAULT_MAX_SIZE,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private val entries = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean =
            size > maxSize
    }

    @Synchronized
    fun isNegative(key: String): Boolean {
        val at = entries[key] ?: return false
        if (nowMs() - at > ttlMs) {
            entries.remove(key)
            return false
        }
        return true
    }

    @Synchronized
    fun markNegative(key: String) {
        entries[key] = nowMs()
    }

    @Synchronized
    fun remove(key: String) {
        entries.remove(key)
    }

    @Synchronized
    fun clear() = entries.clear()

    companion object {
        internal const val DEFAULT_MAX_SIZE = 2048
        internal const val DEFAULT_TTL_MS = 10 * 60_000L
    }
}

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
     * Bounded/TTL'd negative cache: txids the FULL probe chain (including the
     * SDK-DB probes) classified as "not a known asset lock". Without it every
     * 60s display-sync tick re-ran the DataStore + up to four Room probes per
     * internal/incoming row forever (the resolver N+1). Entries are only
     * written when the SDK DB was available (all probes actually ran) and are
     * busted by [seed] (the authoring path learning the kind) or the TTL
     * (post-restore rows that gain an app-side record later re-probe within
     * [NegativeTxidCache.DEFAULT_TTL_MS]).
     */
    private val negative = NegativeTxidCache()

    /**
     * Seed the [kind] for [displayHex] (DISPLAY-order txid hex, any case) so
     * the very first [kindFor] on it returns [kind] without waiting for the
     * DataStore/Room persist to land. Idempotent; keyed lowercase to match
     * [kindFor]'s normalization. Also busts any stale negative-cache entry
     * (belt only — [seeded] is consulted before the negative cache anyway).
     */
    fun seed(displayHex: String, kind: AssetLockKind) {
        val hex = displayHex.lowercase()
        seeded[hex] = kind
        negative.remove(hex)
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
        // A recent full-probe "not an asset lock" verdict: skip the DataStore
        // + Room probe chain this pass (see [negative]).
        if (negative.isNegative(hex)) return null
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
            val kind = when (db?.assetLockDao()?.fundingTypeForTxid(hex)) {
                FUNDING_TYPE_SHIELD -> AssetLockKind.SHIELD
                FUNDING_TYPE_INVITATION -> AssetLockKind.INVITE
                FUNDING_TYPE_TOPUP_A, FUNDING_TYPE_TOPUP_B -> AssetLockKind.TOPUP
                FUNDING_TYPE_UPGRADE -> AssetLockKind.UPGRADE
                else -> null
            }
            // Negative-cache ONLY a verdict every probe could weigh in on: with
            // the SDK DB up, all probes ran and null means "genuinely not a
            // known asset lock" (until seeded or TTL re-probe). With the DB
            // still down the verdict is partial — never cached, so the next
            // pass re-probes (the pre-existing retry semantics).
            if (kind == null && db != null) negative.markNegative(hex)
            kind
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
