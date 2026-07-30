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
import kotlinx.coroutines.CancellationException
import org.dash.wallet.common.data.TxId
import org.dash.wallet.common.data.entity.TransactionMetadata
import org.dash.wallet.common.money.Coin
import org.dash.wallet.common.transactions.TransactionCategory
import org.dashfoundation.dashsdk.keywallet.DecodedTransaction
import org.dashfoundation.dashsdk.keywallet.TransactionDecoder
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
fun SdkTxDetail.toDefaultMetadata(): TransactionMetadata = TransactionMetadata(
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

    // Fee: the SDK-recorded fee wins; otherwise Σin−Σout, but ONLY when
    // every input's spent value is wallet-known (a single unknown input
    // makes the subtraction meaningless). A negative result means
    // inconsistent data — show nothing rather than a fabricated fee.
    val derivedFee = if (
        decoded != null && decoded.inputs.isNotEmpty() &&
        inputTxoValues.size == decoded.inputs.size && inputTxoValues.all { it != null }
    ) {
        (inputTxoValues.filterNotNull().sum() - outputs.sumOf { it.valueDuffs })
            .takeIf { it >= 0 }
    } else {
        null
    }
    val fee = record.feeDuffs ?: derivedFee

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
class SdkTxDetailProvider @Inject constructor(
    private val sdkService: DashSdkService,
    private val txDisplayCacheDao: TxDisplayCacheDao,
    private val assetLockKindResolver: AssetLockKindResolver
) {
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
        val baseRecord = l1TxUiRecord(
            txidWireBytes = entity.txid,
            netAmountDuffs = entity.netAmount,
            feeDuffs = entity.fee,
            contextCode = entity.context,
            directionCode = entity.direction,
            firstSeenSec = entity.firstSeen,
            blockTimestampSec = entity.blockTimestamp
        )

        // A fresh pre-block (mempool/IS-locked) receive/send can have net_amount
        // 0 in the SDK `transactions` row — attribution is not written until the
        // tx is processed into a block. The live engine event DID carry the
        // value, and CutoverUiDataService persisted it to
        // tx_display_cache.valueSatoshis (keyed by lowercase display hex). Prefer
        // that so the detail sheet shows the real amount instead of "0.00 DASH".
        //
        // Sign convention differs by direction (Bug A):
        // - INCOMING: the cached value IS the received net → use it directly.
        // - OUTGOING: planL1TxRow writes the cached value as the PRINCIPAL only
        //   (it excludes the fee — CutoverUiDataService.planL1TxRow), so
        //   reconstruct the true signed net = -(|principal| + fee) so a sent
        //   amount renders the same way the dashj path does (|net| amount, fee
        //   shown separately). INTERNAL/COINJOIN are left untouched.
        val record = if (
            baseRecord.netAmountDuffs == 0L &&
            (baseRecord.direction == L1TxUiDirection.INCOMING ||
                baseRecord.direction == L1TxUiDirection.OUTGOING)
        ) {
            val cachedValue = txDisplayCacheDao
                .getEntriesByIds(listOf(txIdDisplayHex.lowercase()))
                .firstOrNull()
                ?.valueSatoshis
            if (cachedValue != null && cachedValue != 0L) {
                val net = if (baseRecord.direction == L1TxUiDirection.INCOMING) {
                    cachedValue
                } else {
                    -(kotlin.math.abs(cachedValue) + (baseRecord.feeDuffs ?: 0L))
                }
                baseRecord.copy(netAmountDuffs = net)
            } else {
                baseRecord
            }
        } else {
            baseRecord
        }

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

    companion object {
        private val log = LoggerFactory.getLogger(SdkTxDetailProvider::class.java)
    }
}
