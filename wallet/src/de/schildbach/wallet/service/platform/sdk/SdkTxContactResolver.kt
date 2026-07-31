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
     * @param txDisplayHex DISPLAY-order (byte-reversed) txid hex — the same
     *   convention `tx_display_cache` rowIds and `Sha256Hash.toString()` use.
     */
    suspend fun contactFor(txDisplayHex: String): ResolvedTxContact? {
        val hex = txDisplayHex.lowercase()
        if (hex.length != 64 || !hex.all { it.isDigit() || it in 'a'..'f' }) return null
        resolved[hex]?.let { return it }
        return try {
            if (!identityRepo.hasBlockchainIdentity) return null
            val identity = identityRepo.blockchainIdentity ?: return null
            val db = sdkService.databaseOrNull() ?: return null
            // The SDK persists txid in WIRE order; display hex is byte-reversed.
            val wireTxid = hex.chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()
                .reversedArray()
            val entity = db.transactionDao().getByTxid(wireTxid) ?: return null
            val raw = entity.transactionData ?: return null
            // Parse the SDK's own raw bytes into a dashj Transaction — no wallet
            // membership needed, only the OUTPUT addresses for the keychain match.
            val tx = Transaction(walletData.networkParameters, raw)
            // Reuse the dashj DIP-15 resolver: matches an output address against the
            // friendship keychains and returns the contact identity (Base58), or null
            // when no friendship output matches (a plain non-contact tx).
            val userId = identity.getContactForTransaction(tx) ?: return null
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
     * Loads the wallet TXO + account snapshots ONCE for the whole batch (two Room
     * reads, off-main). Fail-soft to an empty map on any error, but never SILENTLY:
     * every bail-out logs why, so a contact row that cannot be verified is visible
     * in the log instead of just staying wrong (post-restore this used to bail
     * silently when the orphan SDK wallet had not been pruned yet and
     * `singleOrNull()` saw two wallets).
     */
    suspend fun signedNetsFor(txDisplayHexes: Set<String>): Map<String, Long> {
        if (txDisplayHexes.isEmpty()) return emptyMap()
        return try {
            val db = sdkService.databaseOrNull() ?: return emptyMap<String, Long>().also {
                log.warn(
                    "TXO-net skipped: SDK database not started — {} contact tx(s) stay unverified this pass",
                    txDisplayHexes.size
                )
            }
            val loadedWalletIds = sdkService.walletManagerOrNull()?.wallets?.value?.keys ?: emptySet()
            val walletIdHex = loadedWalletIds.singleOrNull() ?: return emptyMap<String, Long>().also {
                log.warn(
                    "TXO-net skipped: cannot pick the bound SDK wallet ({} loaded — post-restore " +
                        "orphan not pruned yet?) — {} contact tx(s) stay unverified this pass",
                    loadedWalletIds.size, txDisplayHexes.size
                )
            }
            val walletId = walletIdFromHex(walletIdHex) ?: return emptyMap<String, Long>().also {
                log.warn("TXO-net skipped: malformed SDK wallet id")
            }
            // Watch-only external friendship accounts — their TXOs are the contact's
            // funds, never this wallet's (see KDoc).
            val externalAccountIds = db.accountDao().observeByWallet(walletId).first()
                .filter { it.accountType == ACCOUNT_TYPE_DASHPAY_EXTERNAL }
                .map { it.id }
                .toSet()
            val txos = db.txoDao().observeByWallet(walletId).first()
            val received = HashMap<String, Long>()
            val spent = HashMap<String, Long>()
            for (txo in txos) {
                if (txo.accountId != null && txo.accountId in externalAccountIds) continue
                txo.txid?.let { received.merge(displayHexOf(it), txo.amount, Long::plus) }
                txo.spendingTxid?.let { spent.merge(displayHexOf(it), txo.amount, Long::plus) }
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
    }
}
