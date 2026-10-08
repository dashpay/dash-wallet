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

import de.schildbach.wallet.Constants
import de.schildbach.wallet.database.dao.TxDisplayCacheDao
import de.schildbach.wallet.database.entity.TxDisplayCacheEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dash.wallet.common.data.TxId
import org.dash.wallet.common.data.entity.TransactionMetadata
import org.dash.wallet.common.money.Coin
import org.dash.wallet.common.transactions.TransactionCategory
import org.dashfoundation.dashsdk.keywallet.DecodedTransaction
import org.dashfoundation.dashsdk.keywallet.TransactionDecoder
import org.dashfoundation.dashsdk.persistence.DashDatabase
import org.dashfoundation.dashsdk.persistence.entities.TransactionEntity
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

// ── Neutral detail model (no SDK JNI types, no dashj Transaction) ─────

/**
 * A NEUTRAL, dashj-free detail view of one SDK-persisted L1 transaction —
 * the Step B1 replacement for the tx-detail sheet's dashj `Transaction`
 * lookup when the transaction exists ONLY in the SDK (post-cutover
 * receives/sends the held dashj wallet never saw — the "blank detail
 * sheet" gap).
 *
 * Every field is honestly derivable from the SDK's Room row
 * (`TransactionEntity`), its TXO rows, and the consensus decode of the
 * stored raw bytes (`TransactionDecoder`). Fields dashj derived from live
 * confidence (peer broadcast counts, confirmation depth, exchange rate at
 * send time) have NO honest SDK source and are deliberately absent.
 */
data class SdkTxDetail(
    /** DISPLAY-order (byte-reversed) txid hex — `Sha256Hash.toString()` convention. */
    val txIdDisplayHex: String,
    /** Signed net effect on the wallet in duffs (positive=received, negative=sent incl. fee). */
    val netAmountDuffs: Long,
    /**
     * Fee in duffs when honestly derivable: the SDK-recorded fee
     * (self-authored sends), else Σ(inputs) − Σ(outputs) when EVERY spent
     * input is a wallet-known TXO; null otherwise (e.g. incoming txs,
     * whose input values the wallet never tracked).
     */
    val feeDuffs: Long?,
    /** Epoch-millis of first observation (or the block timestamp), 0 when unknown. */
    val timestampMs: Long,
    /** SDK-recorded lock/confirmation knowledge (`transactions.context`). */
    val status: L1TxUiStatus,
    val direction: L1TxUiDirection,
    /** Sender-side addresses to show ("Sent from"): wallet TXO addresses of the spent inputs. */
    val inputAddresses: List<String>,
    /** Destination addresses to show ("Sent to" / "Received at"), direction-filtered. */
    val outputAddresses: List<String>,
    /** True when the decoded transaction carries at least one OP_RETURN output. */
    val hasOpReturn: Boolean,
    /**
     * True when the raw bytes could not be consensus-decoded (unexpected —
     * logged upstream); amounts/time/status above still come from the Room
     * row, but the address lists are empty and [hasOpReturn] is false.
     */
    val decodeFailed: Boolean = false,
    /**
     * The Platform-funding role of this transaction when it is a wallet-authored
     * asset lock the app-side records identify (identity upgrade / top-up /
     * invite). The SDK records these as INTERNAL moves; classified app-side so
     * the detail sheet renders "sent to" + the "…Fee" title instead of the
     * "moved internally to" internal-move presentation. Null for a plain move.
     */
    val assetLockKind: AssetLockKind? = null
) {
    /** True when the wallet's balance decreased (sent / internal move). */
    val isSent: Boolean get() = netAmountDuffs < 0 || direction == L1TxUiDirection.OUTGOING ||
        direction == L1TxUiDirection.INTERNAL || direction == L1TxUiDirection.COINJOIN

    /**
     * True when every displayed output pays the wallet back (internal move).
     * An identified asset-lock funding tx ([assetLockKind]) is NOT an internal
     * move for display — it funds a Platform action, so it renders "sent to".
     */
    val isInternal: Boolean get() = assetLockKind == null &&
        (direction == L1TxUiDirection.INTERNAL || direction == L1TxUiDirection.COINJOIN)
}

/**
 * A minimal, default (no user-set category / no memo) [TransactionMetadata]
 * row for this SDK-only transaction, keyed by the neutral [TxId].
 *
 * This is the "insert path that takes the txid + value/type from the SDK
 * detail instead of a dashj wallet Transaction": the provider can neither
 * derive nor observe a metadata row for a tx the dashj wallet does not hold,
 * so callers hand this row in as the fallback to persist a user's edit
 * (tax category, memo) against. Its null [TransactionMetadata.taxCategory]
 * lets the sheet fall back to [TransactionMetadata.defaultTaxCategory]
 * (Income for a receive, Expense for a send) — identical to a dashj tx.
 */
fun SdkTxDetail.toDefaultMetadata(): TransactionMetadata =
    defaultSdkTxMetadata(txIdDisplayHex, netAmountDuffs, timestampMs, isSent)

/**
 * The same default row as [SdkTxDetail.toDefaultMetadata], built from the
 * list-shape record alone (no consensus decode, no TXO lookups). The sent/received
 * rule matches [SdkTxDetail.isSent].
 */
internal fun L1TxUiRecord.toDefaultMetadata(): TransactionMetadata = defaultSdkTxMetadata(
    txidHex,
    netAmountDuffs,
    timestampMs,
    isSent = netAmountDuffs < 0 || direction != L1TxUiDirection.INCOMING
)

private fun defaultSdkTxMetadata(
    txIdDisplayHex: String,
    netAmountDuffs: Long,
    timestampMs: Long,
    isSent: Boolean
): TransactionMetadata = TransactionMetadata(
    TxId.wrap(txIdDisplayHex),
    timestampMs,
    Coin.valueOf(netAmountDuffs),
    if (isSent) TransactionCategory.Sent else TransactionCategory.Received
)

/**
 * Pure detail assembly — host-JVM unit-testable without Room or JNI.
 *
 * @param record the row's neutral list-shape (from [l1TxUiRecord]) — carries
 *   net amount, SDK-recorded fee, timestamp, status and direction.
 * @param decoded consensus decode of the stored raw bytes, or null when
 *   decoding failed (detail degrades to row-only fields).
 * @param myOutputAddresses addresses among this tx's outputs the wallet owns
 *   (its TXO rows for this txid).
 * @param inputTxoAddresses per decoded input: the spent TXO's address when
 *   the wallet owns/tracks it, else null. Parallel to `decoded.inputs`.
 * @param inputTxoValues per decoded input: the spent TXO's value in duffs
 *   when known, else null. Parallel to `decoded.inputs`.
 */
internal fun buildSdkTxDetail(
    record: L1TxUiRecord,
    decoded: DecodedTransaction?,
    myOutputAddresses: Set<String>,
    inputTxoAddresses: List<String?>,
    inputTxoValues: List<Long?>,
    // Platform-funding role when this internal move is an identified asset lock
    // (identity upgrade / top-up / invite), else null — see [SdkTxDetail.assetLockKind].
    assetLockKind: AssetLockKind? = null
): SdkTxDetail {
    val outputs = decoded?.outputs.orEmpty()

    // Fee: the SDK-recorded fee wins; otherwise Σin−Σout ([derivedFeeDuffs]).
    val fee = record.feeDuffs ?: derivedFeeDuffs(decoded, inputTxoValues)

    val allOutputAddresses = outputs.mapNotNull { it.address }.distinct()
    val outputAddresses = when (record.direction) {
        L1TxUiDirection.OUTGOING -> {
            // External recipients; when every output pays the wallet back
            // (entirely-self move) fall back to all addresses — the same
            // display dashj's "moved internally to" list produces.
            val external = allOutputAddresses.filter { it !in myOutputAddresses }
            external.ifEmpty { allOutputAddresses }
        }
        L1TxUiDirection.INTERNAL, L1TxUiDirection.COINJOIN -> allOutputAddresses
        // Received: only the outputs that are provably ours. No fallback —
        // showing the sender's change address as "received at" would lie.
        L1TxUiDirection.INCOMING -> allOutputAddresses.filter { it in myOutputAddresses }
    }

    // Sender addresses: shown for wallet-authored spends only (dashj detail
    // parity — a received tx shows no "from"). Wallet TXO rows are the
    // authoritative source; the decoded P2PKH scriptSig hint fills gaps for
    // our own inputs (self-authored, so the hint is our own address), and
    // is never used for INCOMING txs where it would be an unauthenticated
    // third-party claim.
    val inputAddresses = if (record.direction == L1TxUiDirection.INCOMING || decoded == null) {
        emptyList()
    } else {
        decoded.inputs.mapIndexedNotNull { i, input ->
            inputTxoAddresses.getOrNull(i) ?: input.address
        }.distinct()
    }

    return SdkTxDetail(
        txIdDisplayHex = record.txidHex,
        netAmountDuffs = record.netAmountDuffs,
        feeDuffs = fee,
        timestampMs = record.timestampMs,
        status = record.status,
        direction = record.direction,
        inputAddresses = inputAddresses,
        outputAddresses = outputAddresses,
        hasOpReturn = outputs.any { it.scriptPubkey.firstOrNull() == OP_RETURN },
        decodeFailed = decoded == null,
        assetLockKind = assetLockKind
    )
}

private const val OP_RETURN = 0x6a.toByte()

/**
 * Σin−Σout of [decoded], but ONLY when every input's spent value is
 * wallet-known ([inputTxoValues], parallel to `decoded.inputs`): a single
 * unknown input makes the subtraction meaningless. A negative result means
 * inconsistent data — null rather than a fabricated fee. Input TXO values are
 * known before the send confirms, so this also covers a pending send whose
 * store fee is still NULL.
 */
internal fun derivedFeeDuffs(decoded: DecodedTransaction?, inputTxoValues: List<Long?>): Long? =
    if (decoded != null && decoded.inputs.isNotEmpty() &&
        inputTxoValues.size == decoded.inputs.size && inputTxoValues.all { it != null }
    ) {
        (inputTxoValues.filterNotNull().sum() - decoded.outputs.sumOf { it.valueDuffs })
            .takeIf { it >= 0 }
    } else {
        null
    }

/**
 * The true signed wallet net of a transaction whose plain send/receive
 * history row ([cachedValue], non-zero) overrides the SDK record's direction
 * and amount in the detail sheet — so a sent amount renders the dashj way:
 * |net| as the amount, the fee shown separately.
 *
 * - A receive's cached value IS its net.
 * - A contact send's cached value is taken as the engine's signed net
 *   ([SdkTxContactResolver.signedNetsFor]: owned outputs − owned inputs, fee
 *   included). That is only usually right — a contact row written before the
 *   engine net was known holds the fee-free principal — so [SdkTxDetailProvider]
 *   uses this only when it has no walker record to read the net from.
 * - A row still holding the record's fee-included net (cached before the fee
 *   was known, and not yet corrected by [withoutCachedFee]) is the net too.
 * - Every other send is cached as its principal only
 *   ([L1TxUiRecord.sentValueWithoutFeeDuffs]): the net is −(|principal| + fee).
 *
 * The middle two used to be indistinguishable from the last only because the
 * store's fee column was always NULL; once the walker persists recovered fees
 * (D-M-01), adding the fee to them would count it twice. Pure — host-testable.
 */
internal fun detailSignedNet(cachedValue: Long, isContactRow: Boolean, record: L1TxUiRecord): Long = when {
    cachedValue > 0L -> cachedValue
    isContactRow -> cachedValue
    cachedValue == record.netAmountDuffs -> cachedValue
    else -> -(kotlin.math.abs(cachedValue) + (record.feeDuffs ?: 0L))
}

/**
 * The bound SDK wallet's record for [displayHex] through [SdkTxStoreWalker.recordFor],
 * or null when the manager is not up, no single wallet is bound, the tx is not
 * the wallet's, or the read fails — the detail sheet then falls back to the raw
 * store row.
 */
private suspend fun boundWalletRecordOrNull(
    sdkService: DashSdkService,
    db: DashDatabase,
    displayHex: String
): L1TxUiRecord? = try {
    val walletId = sdkService.walletManagerOrNull()?.wallets?.value?.keys?.singleOrNull()?.let(::walletIdFromHex)
    walletId?.let { withContext(Dispatchers.IO) { SdkTxStoreWalker(db, it).recordFor(displayHex) } }
} catch (e: CancellationException) {
    throw e
} catch (t: Throwable) {
    LoggerFactory.getLogger(SdkTxDetailProvider::class.java)
        .warn("wallet record lookup failed for {}; using the store row", displayHex, t)
    null
}

/** Display-order txid hex → 32 wire-order bytes, or null when malformed. */
internal fun displayTxIdToWireBytes(txIdDisplayHex: String): ByteArray? {
    if (txIdDisplayHex.length != 64 || !txIdDisplayHex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
        return null
    }
    return txIdDisplayHex.chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()
        .reversedArray()
}

/** 36-byte TXO outpoint key: 32-byte wire txid + 4-byte little-endian vout. */
internal fun txoOutpoint(wireTxid: ByteArray, vout: Int): ByteArray =
    wireTxid + byteArrayOf(
        (vout and 0xFF).toByte(),
        ((vout ushr 8) and 0xFF).toByte(),
        ((vout ushr 16) and 0xFF).toByte(),
        ((vout ushr 24) and 0xFF).toByte()
    )

// ── The provider ──────────────────────────────────────────────────────

/**
 * Loads an [SdkTxDetail] for a transaction the dashj wallet does NOT hold
 * (post-cutover SDK-only txs) from the SDK's Room store + a consensus
 * decode of the persisted raw bytes via the Step B1 JNI binding
 * ([TransactionDecoder], key-wallet-ffi `transaction_decode`).
 *
 * Read-only over the SDK database (same convention as
 * [DashSdkCutoverUiSource]); never constructs dashj types.
 *
 * Executable coverage for the `transaction_decode` binding itself lives in
 * the SDK's JNI crate: its 7 host-run cargo tests exercise the vendored
 * key-wallet-ffi decode path via the workspace `[patch]` (the runtime code
 * path this provider calls), plus the SDK's Kotlin `TransactionDecoderTest`
 * pinning the cross-language blob format. The vendored key-wallet-ffi tree
 * is excluded from the cargo workspace, so its own 13 unit tests do NOT
 * execute in the SDK build — do not count them as coverage. The wallet-side
 * mapping is covered here by [SdkTxDetailTest].
 */
@Singleton
class SdkTxDetailProvider internal constructor(
    private val sdkService: DashSdkService,
    private val txDisplayCacheDao: TxDisplayCacheDao,
    private val assetLockKindResolver: AssetLockKindResolver,
    // The bound wallet's record for a txid as the history list sees it
    // ([SdkTxStoreWalker.recordFor]): reattributed, with its fee recovered.
    // Null when no wallet is bound or the tx is not the wallet's. A seam so
    // host tests can supply the record without a Room store.
    private val walletRecordFor: suspend (DashDatabase, String) -> L1TxUiRecord?
) : SdkTxMetadataSource {
    @Inject
    constructor(
        sdkService: DashSdkService,
        txDisplayCacheDao: TxDisplayCacheDao,
        assetLockKindResolver: AssetLockKindResolver
    ) : this(
        sdkService,
        txDisplayCacheDao,
        assetLockKindResolver,
        { db, displayHex -> boundWalletRecordOrNull(sdkService, db, displayHex) }
    )

    /**
     * Load the detail for [txIdDisplayHex] (display-order hex, i.e.
     * `Sha256Hash.toString()`), or null when the SDK holds no such
     * transaction (or the SDK store is unavailable).
     */
    suspend fun load(txIdDisplayHex: String): SdkTxDetail? {
        val wireTxid = displayTxIdToWireBytes(txIdDisplayHex.lowercase()) ?: return null
        val db = try {
            sdkService.ensureStarted()
            sdkService.databaseOrNull() ?: return null
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Throwable, not Exception: Sdk.initialize() loads the native
            // library, which throws UnsatisfiedLinkError (or
            // ExceptionInInitializerError) on ABIs the AAR does not ship
            // (only arm64-v8a + x86_64). Degrade to "no SDK detail"
            // instead of crashing the sheet.
            log.warn("SDK unavailable for tx-detail lookup of {}", txIdDisplayHex, t)
            return null
        }

        val entity = db.transactionDao().getByTxid(wireTxid) ?: return null

        val decoded = try {
            TransactionDecoder.decode(
                entity.transactionData,
                toSdkNetwork(Constants.NETWORK_PARAMETERS)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Throwable, not Exception: an Exception here is unexpected for
            // SDK-persisted bytes, while a LinkageError (UnsatisfiedLinkError /
            // ExceptionInInitializerError) means the JNI library cannot load
            // on this device's ABI. Either way, degrade to the row-only
            // detail (same presentation as a decode failure) — never crash.
            log.error("consensus decode failed for SDK tx {}", txIdDisplayHex, t)
            null
        }

        val txoDao = db.txoDao()
        val myOutputAddresses = mutableSetOf<String>()
        decoded?.outputs?.forEachIndexed { index, _ ->
            txoDao.getByOutpoint(txoOutpoint(wireTxid, index))?.let { myOutputAddresses += it.address }
        }
        val inputTxos = decoded?.inputs.orEmpty().map { input ->
            txoDao.getByOutpoint(txoOutpoint(input.prevTxid, input.prevVout))
        }

        // The fee the history list used: the store's, or — while the store still has it
        // NULL — Σin−Σout from the wallet's input TXOs, which the walker also uses
        // (persisted, or in memory for a pending send). See [correctedRecord].
        val record = correctedRecord(
            db,
            entity,
            txIdDisplayHex,
            derivedFee = derivedFeeDuffs(decoded, inputTxos.map { it?.amount })
        )

        // Classify a wallet-authored internal move as a Platform-funding asset
        // lock (upgrade / top-up / invite) so the sheet renders "sent to" + the
        // "…Fee" title instead of "moved internally to". Null for a plain move.
        val assetLockKind = assetLockKindResolver.kindFor(txIdDisplayHex.lowercase())

        return buildSdkTxDetail(
            record = record,
            decoded = decoded,
            myOutputAddresses = myOutputAddresses,
            inputTxoAddresses = inputTxos.map { it?.address },
            inputTxoValues = inputTxos.map { it?.amount },
            assetLockKind = assetLockKind
        )
    }

    /**
     * The record the history list was built from for [entity], with the
     * tx_display_cache correction applied. [derivedFee] is the Σin−Σout fee when
     * the caller decoded the transaction, else null. Cheap: the walker's three
     * indexed queries plus one cache read, no decode.
     */
    private suspend fun correctedRecord(
        db: DashDatabase,
        entity: TransactionEntity,
        txIdDisplayHex: String,
        derivedFee: Long?
    ): L1TxUiRecord {
        // The fee the history list used: the store's, or — while the store still has it
        // NULL — Σin−Σout from the wallet's input TXOs, which the walker also uses
        // (persisted, or in memory for a pending send). It must be known before the
        // cached principal is turned back into a net below, or a pending send's fee is
        // read as 0 and its amount sent shows without it (D-M-01).
        // The record the history list was built from: the walker's, which corrects a
        // misattributed stored row (a contact send stored INCOMING +change becomes
        // OUTGOING with the whole-wallet net) and carries the recovered fee, persisted
        // or in memory for a pending send. Reading it here keeps the sheet and the
        // list on the same net and fee (D-M-01). The raw store row is the fallback
        // when no wallet is bound.
        val walletRecord = walletRecordFor(db, txIdDisplayHex.lowercase())
        val effectiveFee = walletRecord?.feeDuffs ?: entity.fee ?: derivedFee
        val baseRecord = (
            walletRecord ?: l1TxUiRecord(
                txidWireBytes = entity.txid,
                netAmountDuffs = entity.netAmount,
                feeDuffs = entity.fee,
                contextCode = entity.context,
                directionCode = entity.direction,
                firstSeenSec = entity.firstSeen,
                blockTimestampSec = entity.blockTimestamp
            )
            ).copy(feeDuffs = effectiveFee)

        // The tx_display_cache row (written by CutoverUiDataService, keyed by lowercase display
        // hex) carries the FULL home-list correction — engine net AND the DashPay-contact
        // correction. For a contact send the SDK `transactions` row surfaces only the +change
        // (wrong direction: INCOMING / wrong amount), so whenever a plain send/receive cache row
        // exists it is the AUTHORITATIVE direction + amount override — not only when the SDK net
        // is 0. Absent (pre-cutover / non-SDK txs) → the SDK row stands unchanged.
        //
        // Only plain send/receive rows (iconType SENT/RECEIVED) override; INTERNAL/COINJOIN/
        // service rows keep the SDK direction. Sign convention (Bug A):
        // - INCOMING: the cached value IS the received net → use it directly.
        // - OUTGOING: the walker's own net when it has one — it IS the signed net,
        //   fee included, whatever the row holds. A cached row cannot tell a contact
        //   send's engine net (fee included) from a fallback principal (fee excluded),
        //   since both carry the contact identity. Without a walker record,
        //   [detailSignedNet] reconstructs it from the row.
        val cacheEntry = txDisplayCacheDao
            .getEntriesByIds(listOf(txIdDisplayHex.lowercase()))
            .firstOrNull()
        val cachedValue = cacheEntry?.valueSatoshis
        return if (
            cacheEntry != null && cachedValue != null && cachedValue != 0L &&
            (cacheEntry.iconType == TxDisplayCacheEntry.ICON_SENT ||
                cacheEntry.iconType == TxDisplayCacheEntry.ICON_RECEIVED)
        ) {
            val cachedDirection = if (cachedValue < 0) {
                L1TxUiDirection.OUTGOING
            } else {
                L1TxUiDirection.INCOMING
            }
            val net = if (cachedDirection == L1TxUiDirection.OUTGOING &&
                walletRecord != null && walletRecord.netAmountDuffs < 0L
            ) {
                walletRecord.netAmountDuffs
            } else {
                detailSignedNet(cachedValue, isContactRow = cacheEntry.contactUserId != null, baseRecord)
            }
            baseRecord.copy(netAmountDuffs = net, direction = cachedDirection)
        } else {
            baseRecord
        }
    }

    /**
     * [SdkTxMetadataSource]: the default metadata row for a tx the SDK store holds,
     * from the same corrected record the history list shows.
     *
     * Never starts the SDK. When it is not running there is no SDK store to consult,
     * and starting it as a side effect of a metadata lookup (for example a platform
     * metadata sync on a wallet that has not cut over) is not this method's call.
     * The tx-detail sheet starts it through [load] before it imports metadata.
     *
     * No decode, so no Σin−Σout fee. That only matters for a send with no walker
     * record and no stored fee, whose value is then short by the fee.
     */
    override suspend fun defaultMetadataFor(txId: TxId): TransactionMetadata? {
        val txIdDisplayHex = txId.toString().lowercase()
        val wireTxid = displayTxIdToWireBytes(txIdDisplayHex) ?: return null
        return try {
            val db = sdkService.databaseOrNull() ?: return null
            val entity = db.transactionDao().getByTxid(wireTxid) ?: return null
            correctedRecord(db, entity, txIdDisplayHex, derivedFee = null).toDefaultMetadata()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("SDK store lookup for metadata of {} failed", txIdDisplayHex, e)
            null
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(SdkTxDetailProvider::class.java)
    }
}
