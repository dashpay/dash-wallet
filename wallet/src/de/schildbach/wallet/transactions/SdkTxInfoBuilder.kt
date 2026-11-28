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

package de.schildbach.wallet.transactions

import de.schildbach.wallet.service.platform.sdk.L1TxUiDirection
import de.schildbach.wallet.service.platform.sdk.L1TxUiRecord
import de.schildbach.wallet.service.platform.sdk.L1TxUiStatus
import de.schildbach.wallet.service.platform.sdk.SdkSeamTxSnapshot
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.script.ScriptException
import org.bitcoinj.script.ScriptPattern
import org.dash.wallet.common.transactions.TxInfo
import org.dash.wallet.common.transactions.TxInputInfo
import org.dash.wallet.common.transactions.TxOutputInfo
import org.slf4j.LoggerFactory

/**
 * Builds neutral [TxInfo] snapshots from the Kotlin SDK's L1 store — the
 * post-cutover twin of [TxInfoConverter]. Field-by-field semantics:
 *
 * - `txId`, `updateTimeMillis`, `netValueDuffs`, `feeDuffs`: straight from
 *   the SDK `transactions` row ([L1TxUiRecord]).
 * - `isLocked`: SDK context != mempool (islock / in-block / chainlocked).
 *   This is the same rule everywhere except dashj's "PENDING but seen by
 *   more than one broadcast peer" arm ([TxInfoConverter]'s
 *   `numBroadcastPeers() > 1` clause), which has no SDK column — the SDK
 *   records no peer-broadcast data, so an SDK-fed pending tx counts as
 *   locked only once the SDK records a REAL islock or block. This is the
 *   stricter/safe direction: a `LockedTransaction` wait (Maya's
 *   `observeTransactionLocked` after a swap send, CrowdNode's
 *   `topUpAddress` via `waitUntilLocked`) holds until the actual
 *   islock/block instead of unlocking as soon as 2 peers echoed the
 *   broadcast. With typical islock latency of ~1-2s the difference is
 *   negligible; the worst case (a tx that never islocks) waits one block
 *   time. Accepted, not a gap to "fix" — never claim a lock the SDK
 *   didn't record.
 * - `isPending`: "not yet mined" — SDK context in {mempool,
 *   islock-without-block} — mirroring dashj's PENDING confidence type,
 *   which STAYS pending when an islock arrives. Pending and locked are
 *   therefore NOT mutually exclusive: an islocked-but-unmined tx is
 *   pending AND locked, exactly like the dashj-fed [TxInfoConverter]
 *   pair (Coinbase's transfer success check reads isPending right after
 *   a send and must not see false on a fast islock).
 * - `isEntirelySelf`: SDK direction is internal/coinjoin (the SDK's own
 *   all-inputs-and-outputs-mine classification).
 * - `inputs`/`outputs`: lazily parsed from the row's raw transaction bytes
 *   with dashj (parsing only — the transaction is never given to a wallet).
 *   An input's connected output is resolved through [SdkSeamTxSnapshot.payloadByTxid]
 *   (any SDK-known tx) with a dashj-held-wallet fallback for txs the SDK
 *   store doesn't have; unresolvable inputs get null connectedAddress /
 *   connectedIsMine, exactly like an unconnected dashj input.
 * - `isMine`/`connectedIsMine`: membership in the SDK TXO set
 *   ([SdkSeamTxSnapshot.mineOutpoints]) — the same output universe dashj's
 *   `isMine(bag)` covers (parity-proven by the shadow harness).
 * - `spentBy`: one level deep via the TXO rows' `spendingTxid`, mirroring
 *   [TxInfoConverter]'s depth-1 rule.
 * - `rawHex`: hex of the SDK's raw transaction bytes.
 * - `raw`: null — there IS no live dashj wallet transaction behind an
 *   SDK-fed snapshot, and fabricating a detached one would invite casts
 *   ([dashjTx]) onto an object with meaningless confidence state. All
 *   current `raw` consumers sit on the `wrapAllTransactions` path, which
 *   stays dashj-fed.
 */
object SdkTxInfoBuilder {
    private val log = LoggerFactory.getLogger(SdkTxInfoBuilder::class.java)

    /**
     * Builds `TxInfo`s for every record in [SdkSeamTxSnapshot.walletRecords],
     * keyed by display txid hex, preserving the row order. NOTE
     * (scalability): production seam snapshots carry only a BOUNDED delta in
     * `walletRecords`, and the seam service builds per-record infos on demand
     * via [buildTxInfo] instead — this bulk entry point serves fixtures and
     * callers that hold genuinely small snapshots.
     *
     * @param dashjTxLookup fallback lookup into the HELD dashj wallet for
     *        connected-output resolution when the SDK store doesn't know
     *        the source tx (pre-SDK history edge cases). Never used for
     *        the wallet-relevant set itself.
     */
    fun buildTxInfos(
        snapshot: SdkSeamTxSnapshot,
        params: NetworkParameters,
        dashjTxLookup: (Sha256Hash) -> Transaction?
    ): Map<String, TxInfo> {
        val recordByTxid = snapshot.walletRecords.associateBy { it.txidHex }
        val builder = Builder(snapshot, params, dashjTxLookup) { recordByTxid[it] }
        val result = LinkedHashMap<String, TxInfo>(snapshot.walletRecords.size)
        for (record in snapshot.walletRecords) {
            result[record.txidHex] = builder.txInfoOf(record, depth = 1)
        }
        return result
    }

    /**
     * Builds ONE record's `TxInfo` — the bounded, on-demand path the
     * post-cutover seam uses ([de.schildbach.wallet.service.platform.sdk.CutoverTxSeamService]):
     * nothing is materialized beyond this transaction (inputs/outputs stay
     * lazy, `spentBy` resolves depth-1 through the snapshot's
     * [SdkSeamTxSnapshot.recordByTxid] point lookup).
     */
    fun buildTxInfo(
        record: L1TxUiRecord,
        snapshot: SdkSeamTxSnapshot,
        params: NetworkParameters,
        dashjTxLookup: (Sha256Hash) -> Transaction?
    ): TxInfo = Builder(snapshot, params, dashjTxLookup, snapshot.recordByTxid).txInfoOf(record, depth = 1)

    /** Shared build logic; [recordLookup] resolves `spentBy` spender records. */
    private class Builder(
        private val snapshot: SdkSeamTxSnapshot,
        private val params: NetworkParameters,
        private val dashjTxLookup: (Sha256Hash) -> Transaction?,
        private val recordLookup: (String) -> L1TxUiRecord?
    ) {
        private val parseCache = HashMap<String, Transaction?>()

        private fun parsedTx(txidHex: String): Transaction? {
            if (txidHex in parseCache) return parseCache[txidHex]
            val tx = snapshot.payloadByTxid[txidHex]?.let { payload ->
                try {
                    Transaction(params, payload)
                } catch (t: Exception) {
                    log.warn("failed to parse SDK tx payload for {}", txidHex, t)
                    null
                }
            } ?: try {
                dashjTxLookup(Sha256Hash.wrap(txidHex))
            } catch (t: Exception) {
                null
            }
            parseCache[txidHex] = tx
            return tx
        }

        private fun inputsOf(txidHex: String): List<TxInputInfo> {
            val tx = parsedTx(txidHex) ?: return emptyList()
            return tx.inputs.map { input ->
                val outpoint = input.outpoint
                val sourceHex = outpoint.hash.toString()
                val index = outpoint.index.toInt()
                val sourceOutput = parsedTx(sourceHex)?.let { source ->
                    if (index >= 0 && index < source.outputs.size) source.getOutput(index.toLong()) else null
                }
                TxInputInfo(
                    connectedAddress = sourceOutput?.let { output ->
                        try {
                            TxInfoConverter.standardAddressOf(output.scriptPubKey, params)
                        } catch (x: ScriptException) {
                            null
                        }
                    },
                    connectedIsMine = if (sourceOutput != null) {
                        "$sourceHex:$index" in snapshot.mineOutpoints
                    } else {
                        null
                    }
                )
            }
        }

        fun txInfoOf(record: L1TxUiRecord, depth: Int): TxInfo {
            val txidHex = record.txidHex
            return TxInfo(
                txId = txidHex,
                updateTimeMillis = record.timestampMs,
                isLocked = record.status != L1TxUiStatus.PENDING,
                // dashj parity: PENDING confidence means "not yet mined", so an
                // islocked-but-unmined tx is pending AND locked (see class docs).
                isPending = record.status == L1TxUiStatus.PENDING ||
                    record.status == L1TxUiStatus.INSTANT_LOCKED,
                netValueDuffs = { record.netAmountDuffs },
                feeDuffs = { record.feeDuffs },
                isEntirelySelf = {
                    record.direction == L1TxUiDirection.INTERNAL ||
                        record.direction == L1TxUiDirection.COINJOIN
                },
                inputs = { inputsOf(txidHex) },
                outputs = {
                    val tx = parsedTx(txidHex)
                    tx?.outputs?.mapIndexed { index, output ->
                        val script = try {
                            output.scriptPubKey
                        } catch (x: ScriptException) {
                            null
                        }
                        val outpointKey = "$txidHex:$index"
                        TxOutputInfo(
                            valueDuffs = output.value.value,
                            address = script?.let { TxInfoConverter.standardAddressOf(it, params) },
                            isOpReturn = script != null && ScriptPattern.isOpReturn(script),
                            isMine = outpointKey in snapshot.mineOutpoints,
                            index = index,
                            spentBy = if (depth > 0) {
                                snapshot.spenderByOutpoint[outpointKey]
                                    ?.let { spenderHex -> recordLookup(spenderHex) }
                                    ?.let { spender -> txInfoOf(spender, depth - 1) }
                            } else {
                                null
                            }
                        )
                    } ?: emptyList()
                },
                rawHex = {
                    snapshot.payloadByTxid[txidHex]?.joinToString("") { "%02x".format(it) }
                },
                raw = null
            )
        }
    }
}
