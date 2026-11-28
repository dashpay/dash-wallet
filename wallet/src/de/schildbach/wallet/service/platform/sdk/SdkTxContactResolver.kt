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

import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.service.platform.IdentityRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.bitcoinj.core.Transaction
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The DashPay contact a post-cutover L1 row pays to / receives from — IDENTITY
 * only (username / display name / avatar / userId). Direction and amount are NOT
 * carried here: they come from the engine's authoritative signed wallet net in
 * [CutoverUiDataService], not from this resolver (see class doc).
 */
data class ResolvedTxContact(
    val username: String,
    val displayName: String?,
    val avatarUrl: String?,
    val userId: String
)

/**
 * Resolves the DashPay contact IDENTITY for one post-cutover SDK L1 transaction
 * the home screen renders from [CutoverUiDataService].
 *
 * ## Why not the dashj wallet, and why identity-only
 *
 * Post-cutover the SDK OWNS the L1 transactions and the HELD dashj wallet does
 * NOT contain them (`L1Parity MISMATCH … tx sdk=7 dashj=2`), so a
 * `Wallet.getTransaction(txid)` lookup returns null for exactly the contact
 * payments this resolver must attribute. So we take the transaction from the SDK's
 * OWN store ([org.dashfoundation.dashsdk.persistence.dao.TransactionDao.getByTxid]
 * → the raw `transactionData` bytes), parse it into a dashj [Transaction], and hand
 * THAT object to the existing DIP-15 resolver
 * [org.dashj.platform.dashpay.BlockchainIdentity.getContactForTransaction] — a pure
 * address→keychain→identity lookup that needs only the friendship keychains (intact
 * in the held wallet) + the parsed outputs, NOT wallet membership of the tx.
 *
 * Direction and amount are deliberately NOT computed here. Earlier attempts keyed
 * them off the SDK record's net (wrong: for a friendship send the SDK surfaces the
 * wallet's own +change output, not the −payment) and off matching outputs to the
 * contact's ISSUED send/receive addresses (unreliable on-device: the send output's
 * address was derived/watched but not in the issued set). The authoritative signal
 * is the engine's own wallet-side signed net (`net_amount` = Σin−Σout over resolved
 * wallet inputs/outputs), which [CutoverUiDataService] captures at event ingest and
 * threads into the planner. This resolver's sole job is contact IDENTITY.
 *
 * Mirrors [AssetLockKindResolver]: fast, fail-soft to null (no identity, tx not in
 * the SDK store, unparseable, no friendship match, unknown profile). Positive
 * resolutions are process-cached (a tx's contact never changes).
 */
@Singleton
class SdkTxContactResolver @Inject constructor(
    private val walletData: WalletData,
    private val identityRepo: IdentityRepository,
    private val dashPayProfileDao: DashPayProfileDao,
    /**
     * The SDK lifecycle owner — the same [DashSdkService.databaseOrNull] accessor
     * [AssetLockKindResolver] and [DashSdkCutoverUiSource] read. Used read-only to
     * fetch the persisted raw transaction bytes by txid (`transactionDao().getByTxid`).
     * `databaseOrNull()` is a non-blocking snapshot: null before the SDK has started,
     * in which case resolution is skipped (fail-closed).
     */
    private val sdkService: DashSdkService
) {
    /**
     * Process-lifetime positive cache keyed by DISPLAY-order (byte-reversed)
     * lowercase txid hex. Only successful resolutions are cached — a null (identity
     * or contacts not loaded yet, tx not yet persisted) is retried on the next pass.
     * A contact never changes for a given tx, so a cached hit is always correct.
     */
    private val resolved = ConcurrentHashMap<String, ResolvedTxContact>()

    /**
     * Bounded/TTL'd NEGATIVE cache — txids the FULL DIP-15 resolution walked
     * and found no friendship match for. Without it, every un-attributed row
     * re-ran the whole probe chain (Room tx fetch + raw-byte parse + keychain
     * walk) on every 60s display-sync tick forever — the resolver N+1.
     * Only the definitive "resolver ran with all prerequisites and matched
     * nothing" verdict is cached; every TRANSIENT null (identity not loaded,
     * SDK DB down, tx not persisted yet, profile row missing) keeps the
     * pre-existing retry-next-pass semantics. Busted by [clearNegativeCache]
     * (wired to [CutoverUiDataService.requestContactReResolution] — the
     * post-restore / added-contact re-resolution signal, exactly the moment a
     * previously-unmatched tx can start matching) and by the TTL belt.
     */
    private val negative = NegativeTxidCache()

    /**
     * Bust every cached negative verdict — called when contacts / friendship
     * keychains are (re)established so the next pass genuinely re-resolves
     * (the post-restore re-resolution flow must never be blocked by a stale
     * "no match" cached while the keychains were still being recovered).
     */
    fun clearNegativeCache() = negative.clear()

    /**
     * @param txDisplayHex DISPLAY-order (byte-reversed) txid hex — the same
     *   convention `tx_display_cache` rowIds and `Sha256Hash.toString()` use.
     */
    suspend fun contactFor(txDisplayHex: String): ResolvedTxContact? {
        val hex = txDisplayHex.lowercase()
        if (hex.length != 64 || !hex.all { it.isDigit() || it in 'a'..'f' }) return null
        resolved[hex]?.let { return it }
        if (negative.isNegative(hex)) return null
        return try {
            if (!identityRepo.hasBlockchainIdentity) return null
            val identity = identityRepo.blockchainIdentity ?: return null
            val db = sdkService.databaseOrNull() ?: return null
            // The SDK persists txid in WIRE order; display hex is byte-reversed.
            val wireTxid = hexToBytesOrNull(hex)?.reversedArray() ?: return null
            val entity = db.transactionDao().getByTxid(wireTxid) ?: return null
            val raw = entity.transactionData ?: return null
            // Parse the SDK's own raw bytes into a dashj Transaction — no wallet
            // membership needed, only the OUTPUT addresses for the keychain match.
            val tx = Transaction(walletData.networkParameters, raw)
            // Reuse the dashj DIP-15 resolver: matches an output address against the
            // friendship keychains and returns the contact identity (Base58), or null
            // when no friendship output matches (a plain non-contact tx).
            val userId = identity.getContactForTransaction(tx)
            if (userId == null) {
                // The DEFINITIVE no-match: identity + tx + keychains were all
                // present and the resolver matched nothing. Cache it so the 60s
                // ticker stops re-walking the keychains for this txid (busted by
                // clearNegativeCache when a contact is added / restored).
                negative.markNegative(hex)
                return null
            }
            // Profile not synced yet is TRANSIENT — retried next pass, never cached.
            val profile = dashPayProfileDao.loadByUserId(userId) ?: return null
            ResolvedTxContact(
                username = profile.username,
                displayName = profile.displayName.ifEmpty { null },
                avatarUrl = profile.avatarUrl.ifEmpty { null },
                userId = profile.userId
            ).also { resolved[hex] = it }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log.warn("contact lookup failed for {}; row stays un-attributed", hex, t)
            null
        }
    }

    /**
     * The RESTART-SAFE signed wallet net for each of [txDisplayHexes], derived from
     * the SDK's persisted TXO table so it is computable for ANY tx at any time —
     * unlike the live [L1TxEvent.Detected] net (in [CutoverUiDataService.engineNetByTxid]),
     * which only fires while a tx is pre-block and is empty after restart for an
     * already-confirmed tx.
     *
     * For each txid:
     *   signedNet = Σ(amount of wallet TXOs this tx CREATED, `txo.txid == txid`)
     *             − Σ(amount of wallet TXOs this tx SPENT,   `txo.spendingTxid == txid`)
     *
     * = the wallet's own Σin−Σout, matching the engine's `net_amount`. For the 0.1
     * friendship send it is +3.83 (change back) − 3.94 (inputs spent) = −0.11 ≈ −0.1
     * (incl. fee) — the correct SEND value, regardless of the wrong `transactions.netAmount`
     * column. A net of 0 (neither side present — tx not wallet-affecting, or TXOs not
     * yet written) is omitted so the caller falls back cleanly.
     *
     * WATCH-ONLY EXCLUSION (verified on-device, S22 sender): the SDK's `txos` table
     * also holds rows for the watch-only `dashpayExternalAccount` — the DIP-15 chain
     * WE pay a contact on, i.e. the CONTACT's funds. Counting those as wallet-created
     * turns a self-authored 0.05 friendship send into +0.05 − (0.05+fee+change−change)
     * ≈ −fee, or (when the spent inputs' marks are attributed elsewhere) a bare
     * +0.05 "receive" — the observed `iconType=RECEIVED +5000000` rows. TXOs whose
     * `accountId` belongs to a [ACCOUNT_TYPE_DASHPAY_EXTERNAL] account are therefore
     * excluded from BOTH sums: they are never this wallet's money. The receiver's own
     * `dashpayReceivingFunds` (type 12) TXOs are kept — those really are its funds.
     *
     * NULL-`accountId` CLASSIFICATION (verified on-device, S22 `ca05a582…` /
     * `6cee34d3…`): the persistence layer writes friendship-send TXOs with
     * `accountId` NULL, so the exclusion above never fired and the −0.05 send
     * displayed as −fee (−226). The authoritative fallback is the `core_addresses`
     * table: every such TXO carries `coreAddressId` (= its address, the
     * `core_addresses` PK), and `core_addresses.accountId` IS populated —
     * the 0.05 payment output joins to a type-13 `dashpayExternalAccount` row
     * (path `m/9'/1'/15'/0'/<theirId>/<ourId>/…`) while the change output joins to
     * the type-0 BIP44 account. So the external-account exclusion also fires when
     * the TXO's ADDRESS belongs to an external account's `core_addresses` set
     * (fetched per external account via `coreAddressDao().observeByAccount`).
     * `transaction_account_involvements` was empty for these txs on-device and
     * carries no amounts — not usable. If neither `accountId` nor the address
     * classifies the TXO, it is kept (previous behavior; the outer WARN covers
     * hard failures).
     *
     * BOUNDED read: the account snapshot plus a chunked raw IN query fetching ONLY
     * the TXO rows touching the requested txids (the old full-table
     * `observeByWallet().first()` materialized every wallet TXO per pass — the
     * multi-day-sync "too many records" failure class). Fail-soft to an empty map
     * on any error, but never SILENTLY:
     * every bail-out logs why, so a contact row that cannot be verified is visible
     * in the log instead of just staying wrong (post-restore this used to bail
     * silently when the orphan SDK wallet had not been pruned yet and
     * `singleOrNull()` saw two wallets).
     */
    suspend fun signedNetsFor(txDisplayHexes: Set<String>): Map<String, Long> {
        if (txDisplayHexes.isEmpty()) return emptyMap()
        return try {
            val ctx = foreignExclusionContext("${txDisplayHexes.size} contact tx(s) stay unverified this pass")
                ?: return emptyMap()
            val db = ctx.db
            val walletId = ctx.walletId
            val externalAccountIds = ctx.externalAccountIds
            val externalAddresses = ctx.externalAddresses
            // BOUNDED TXO read (multi-day-sync fix): the old code materialized the
            // ENTIRE wallet `txos` table (observeByWallet(walletId).first()) on every
            // pass just to sum a handful of contact txids — on a very large wallet
            // that is tens of thousands of Room entities per 60s tick and the exact
            // "too many records" CursorWindow/memory failure class. The SDK AAR's DAO
            // has no per-txid-set query, so read the four needed columns via a raw
            // chunked IN query on the Room handle (same 999-variable cap discipline
            // as syncDisplayCache's 500-chunks).
            val wireTxidByHex = HashMap<String, ByteArray>(txDisplayHexes.size)
            for (hex in txDisplayHexes) {
                hexToBytesOrNull(hex)?.reversedArray()?.let { wireTxidByHex[hex] = it }
            }
            val received = HashMap<String, Long>()
            val spent = HashMap<String, Long>()
            // A row can satisfy the txid IN of one chunk AND the spendingTxid IN
            // of another, so it may come back from two chunk queries — each row
            // must contribute exactly once (the old full-table loop's semantics),
            // hence the outpoint-PK dedup.
            val seenOutpoints = HashSet<String>()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                for (chunk in wireTxidByHex.values.chunked(SIGNED_NET_TXID_CHUNK)) {
                    val placeholders = chunk.joinToString(",") { "?" }
                    val sql = "SELECT outpoint, txid, spendingTxid, amount, accountId, coreAddressId, address " +
                        "FROM txos WHERE walletId = ? AND " +
                        "(txid IN ($placeholders) OR spendingTxid IN ($placeholders))"
                    val args = ArrayList<Any?>(1 + chunk.size * 2)
                    args.add(walletId)
                    args.addAll(chunk)
                    args.addAll(chunk)
                    db.openHelper.readableDatabase.query(
                        androidx.sqlite.db.SimpleSQLiteQuery(sql, args.toTypedArray())
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            if (!seenOutpoints.add(wireHexOf(cursor.getBlob(0)))) continue
                            val txid = if (cursor.isNull(1)) null else cursor.getBlob(1)
                            val spendingTxid = if (cursor.isNull(2)) null else cursor.getBlob(2)
                            val amount = cursor.getLong(3)
                            val accountId = if (cursor.isNull(4)) null else cursor.getLong(4)
                            val coreAddressId = if (cursor.isNull(5)) null else cursor.getString(5)
                            val address = if (cursor.isNull(6)) null else cursor.getString(6)
                            // Same exclusion rules as before (see KDoc): external
                            // (watch-only, contact's) accounts by accountId, or —
                            // when accountId is NULL — by the address joining to an
                            // external account's core_addresses set.
                            if (accountId != null && accountId in externalAccountIds) continue
                            if (accountId == null && (coreAddressId ?: address) in externalAddresses) continue
                            txid?.let { received.merge(displayHexOf(it), amount, Long::plus) }
                            spendingTxid?.let { spent.merge(displayHexOf(it), amount, Long::plus) }
                        }
                    }
                }
            }
            val out = HashMap<String, Long>()
            for (hex in txDisplayHexes) {
                val net = (received[hex] ?: 0L) - (spent[hex] ?: 0L)
                if (net != 0L) out[hex] = net
            }
            out
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log.warn("TXO-net computation failed; contact rows fall back to the record net", t)
            emptyMap()
        }
    }

    /**
     * Everything a foreign-excluded (`NOT` watch-only external friendship
     * account) read of the TXO mirror needs: the started DB, the single bound
     * wallet id, and the external-account exclusion sets. Null (with a warn
     * carrying [skipDetail]) whenever any prerequisite is missing — callers
     * keep their retry-next-pass semantics.
     */
    private class ForeignExclusionContext(
        val db: org.dashfoundation.dashsdk.persistence.DashDatabase,
        val walletId: ByteArray,
        val externalAccountIds: Set<Long>,
        val externalAddresses: Set<String>
    )

    private suspend fun foreignExclusionContext(skipDetail: String): ForeignExclusionContext? {
        val db = sdkService.databaseOrNull() ?: return null.also {
            log.warn("TXO read skipped: SDK database not started — {}", skipDetail)
        }
        val loadedWalletIds = sdkService.walletManagerOrNull()?.wallets?.value?.keys ?: emptySet()
        val walletIdHex = loadedWalletIds.singleOrNull() ?: return null.also {
            log.warn(
                "TXO read skipped: cannot pick the bound SDK wallet ({} loaded — post-restore " +
                    "orphan not pruned yet?) — {}",
                loadedWalletIds.size, skipDetail
            )
        }
        val walletId = walletIdFromHex(walletIdHex) ?: return null.also {
            log.warn("TXO read skipped: malformed SDK wallet id")
        }
        // Watch-only external friendship accounts — their TXOs are the contact's
        // funds, never this wallet's (see KDoc).
        val externalAccountIds = db.accountDao().observeByWallet(walletId).first()
            .filter { it.accountType == ACCOUNT_TYPE_DASHPAY_EXTERNAL }
            .map { it.id }
            .toSet()
        // Addresses OWNED by the external (watch-only, contact's) accounts —
        // the classification fallback for TXOs persisted with a NULL accountId
        // (see KDoc). `core_addresses` is keyed by address and its accountId is
        // populated even when the TXO's is not. One small read per external
        // account (one per friendship).
        val externalAddresses = HashSet<String>()
        for (accountId in externalAccountIds) {
            db.coreAddressDao().observeByAccount(accountId).first()
                .mapTo(externalAddresses) { it.address }
        }
        return ForeignExclusionContext(db, walletId, externalAccountIds, externalAddresses)
    }

    /**
     * Whether THIS WALLET's own (foreign-excluded) TXOs are involved in the
     * given tx at all — funded by it OR spent by it. The engine's per-account
     * `Detected` events carry no account identity, so an event alone cannot
     * distinguish this wallet's money moving from a CONTACT spending the
     * coins we merely watch on the DIP-15 external friendship account
     * (verified live, S21 testnet 11.10.74: a contact's pooled max-send spent
     * the 0.2 we had paid them, the engine emitted an OUTGOING −0.2 event to
     * us, and the display authored "Sent −0.2" for a tx that actually PAID us
     * 0.5). This is the missing discriminator: `false` = the tx never touched
     * our money (any negative event for it is watch-only noise); `null` = the
     * mirror can't answer right now (DB not started / wallet unresolved /
     * rows not yet persisted — the caller should retry or fall back).
     */
    suspend fun ownedInvolvementFor(txDisplayHex: String): Boolean? {
        return try {
            val ctx = foreignExclusionContext("tx $txDisplayHex owned-involvement unknown") ?: return null
            val wire = hexToBytesOrNull(txDisplayHex)?.reversedArray() ?: return null
            var sawAnyRow = false
            var sawOwnedRow = false
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val sql = "SELECT amount, accountId, coreAddressId, address " +
                    "FROM txos WHERE walletId = ? AND (txid = ? OR spendingTxid = ?)"
                ctx.db.openHelper.readableDatabase.query(
                    androidx.sqlite.db.SimpleSQLiteQuery(sql, arrayOf(ctx.walletId, wire, wire))
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        sawAnyRow = true
                        val accountId = if (cursor.isNull(1)) null else cursor.getLong(1)
                        val coreAddressId = if (cursor.isNull(2)) null else cursor.getString(2)
                        val address = if (cursor.isNull(3)) null else cursor.getString(3)
                        // Same exclusion rules as signedNetsFor (see KDoc).
                        if (accountId != null && accountId in ctx.externalAccountIds) continue
                        if (accountId == null && (coreAddressId ?: address) in ctx.externalAddresses) continue
                        sawOwnedRow = true
                        break
                    }
                }
            }
            when {
                sawOwnedRow -> true
                // Rows exist but every one is the contact's watch-only money —
                // a DEFINITIVE "not our tx". No rows at all stays null: the
                // mirror may simply not have persisted this tx yet.
                sawAnyRow -> false
                else -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log.warn("owned-involvement probe failed for {}", txDisplayHex, t)
            null
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(SdkTxContactResolver::class.java)

        /**
         * `AccountTypeTagFFI::DashpayExternalAccount` — the `accounts.account_type`
         * value the SDK persists for the WATCH-ONLY external friendship account (the
         * DIP-15 chain we pay a contact on; the contact's funds). Value from
         * rs-platform-wallet-ffi `wallet_restore_types.rs`, confirmed against the
         * AAR's `PlatformWalletPersistenceHandlerKt.accountTypeName` mapping
         * (12 = dashpayReceivingFunds, 13 = dashpayExternalAccount) — the same tag
         * space as [ACCOUNT_TYPE_TAG_DASHPAY_RECEIVING_FUNDS] (= 12) in
         * [SdkL1SendService].
         */
        internal const val ACCOUNT_TYPE_DASHPAY_EXTERNAL = 13

        /**
         * Per-chunk txid count of the [signedNetsFor] raw IN query: each chunk
         * binds walletId + 2× the txids, so 400 keeps every statement well
         * under SQLite's 999-variable cap (same discipline as the display
         * sync's 500-chunks over a single IN list).
         */
        internal const val SIGNED_NET_TXID_CHUNK = 400
    }
}
