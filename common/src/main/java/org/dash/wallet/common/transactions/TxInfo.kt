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

package org.dash.wallet.common.transactions

import java.time.LocalDate
import java.time.ZoneId

/**
 * A wallet-relative view of one transaction input, dashj-free.
 *
 * @param connectedAddress base58 destination of the connected (spent) output when it is a
 *        standard P2PKH/P2SH script, null when the connected output is unknown or non-standard.
 * @param connectedIsMine whether the connected output belongs to the wallet; null when the
 *        connected output is unknown.
 */
class TxInputInfo(
    val connectedAddress: String?,
    val connectedIsMine: Boolean?
)

/**
 * A wallet-relative view of one transaction output, dashj-free.
 *
 * @param valueDuffs output value in duffs.
 * @param address base58 destination when the script is standard P2PKH/P2SH, null otherwise.
 * @param isOpReturn true when the output script is an OP_RETURN.
 * @param isMine true when the output pays a wallet address.
 * @param index the output's index in the transaction.
 * @param spentBy the transaction spending this output, if known (populated one level deep).
 */
class TxOutputInfo(
    val valueDuffs: Long,
    val address: String?,
    val isOpReturn: Boolean = false,
    val isMine: Boolean = false,
    val index: Int = 0,
    val spentBy: TxInfo? = null
)

/**
 * A wallet-relative, dashj-free view of a transaction, produced by the wallet module for
 * consumption by feature/integration modules (tx matchers, wrappers, filters).
 *
 * Snapshot semantics differ per field: [isLocked] and [isPending] are true conversion-time
 * snapshots, while [netValueDuffs], [feeDuffs], [isEntirelySelf], [inputs], [outputs] and
 * [rawHex] are lazy closures over the LIVE underlying transaction, frozen at first access —
 * so they reflect the transaction's state at whatever point they are first read, not at
 * conversion time. This keeps filtering large transaction sets as cheap as it was on the
 * dashj types.
 *
 * @param raw an OPAQUE handle to the underlying wallet transaction. Only the wallet module,
 *        which created this snapshot, may cast it back; dashj-free modules must ignore it.
 */
class TxInfo(
    /** Transaction id as a hex string (`Sha256Hash.toString()`). */
    val txId: String,
    /** `Transaction.getUpdateTime().getTime()`, or 0 when unknown. */
    val updateTimeMillis: Long,
    /**
     * Whether the transaction counts as locked/confirmed for matching purposes — IS-locked,
     * mined (BUILDING), or pending but seen by more than one broadcast peer; snapshot at
     * conversion time.
     */
    val isLocked: Boolean = false,
    /** Mirrors `Transaction.isPending` (confidence type PENDING); snapshot at conversion time. */
    val isPending: Boolean = false,
    netValueDuffs: () -> Long = { 0L },
    feeDuffs: () -> Long? = { null },
    isEntirelySelf: () -> Boolean = { false },
    inputs: () -> List<TxInputInfo> = { emptyList() },
    outputs: () -> List<TxOutputInfo> = { emptyList() },
    rawHex: () -> String? = { null },
    val raw: Any? = null
) {
    /** Net value to the wallet in duffs (`Transaction.getValue(bag)`). */
    val netValueDuffs: Long by lazy(netValueDuffs)

    /** Transaction fee in duffs, or null when unknown (`Transaction.getFee()`). */
    val feeDuffs: Long? by lazy(feeDuffs)

    /** True when every input spends a wallet output and every output pays the wallet. */
    val isEntirelySelf: Boolean by lazy(isEntirelySelf)

    val inputs: List<TxInputInfo> by lazy(inputs)

    val outputs: List<TxOutputInfo> by lazy(outputs)

    /** Serialized transaction hex (`Transaction.toStringHex()`), or null when unavailable. */
    val rawHex: String? by lazy(rawHex)

    /** The transaction's update time as a local date, as used for grouping. */
    val groupDate: LocalDate
        get() = java.util.Date(updateTimeMillis).toInstant().atZone(ZoneId.systemDefault()).toLocalDate()

    override fun equals(other: Any?): Boolean = other is TxInfo && other.txId == txId
    override fun hashCode(): Int = txId.hashCode()
    override fun toString(): String = "TxInfo($txId)"
}
