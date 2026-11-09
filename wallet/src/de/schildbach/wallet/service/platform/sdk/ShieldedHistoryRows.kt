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

import androidx.annotation.StringRes
import de.schildbach.wallet.database.entity.TxDisplayCacheEntry
import de.schildbach.wallet_test.R

// ── Shielded activity → Home history rows ─────────────────────────────
//
// Pure — host-testable. The Home history (Room `tx_display_cache`) shows L1
// rows and, since schema 23, one row per shielded-pool activity entry that
// has NO Core transaction of its own (a username paid from the pool, a
// shielded send, an unshield to a Platform address, …). The Home total is
// L1 + shielded, so each row's value is the entry's effect on that total.
//
// De-dup rule: kinds whose operation IS a Core transaction —
// ShieldFromAssetLock (1, Core asset lock → pool) and Withdrawal (5, pool →
// Core) — already render as an L1 row ("Shielded" / "Unshielded" /
// "Withdrawal" via AssetLockKind), so they never get a shielded row. Their
// ids could not collide anyway (shielded ids carry the `shielded:` prefix,
// L1 ids are bare txid hex or group ids), but two rows for one operation
// would double-count it in the history.

/** Prefix of every shielded-activity row id in `tx_display_cache`. */
const val SHIELDED_HISTORY_ROW_PREFIX = "shielded:"

/** `shielded:<accountIndex>:<entryIdHex>` — the activity row's full key, never a txid. */
fun shieldedHistoryRowId(accountIndex: Int, entryIdHex: String): String =
    "$SHIELDED_HISTORY_ROW_PREFIX$accountIndex:${entryIdHex.lowercase()}"

/**
 * Whether a history row id names a shielded-activity row. Such ids must open
 * the Platform activity detail and must never reach `Sha256Hash.wrap`.
 */
fun isShieldedActivityRowId(rowId: String): Boolean = rowId.startsWith(SHIELDED_HISTORY_ROW_PREFIX)

/** The (accountIndex, entryIdHex) key of a shielded row id. */
data class ShieldedActivityRowKey(val accountIndex: Int, val entryIdHex: String)

private val ENTRY_ID_HEX = Regex("^[0-9a-f]{64}$")

/**
 * Parse a [shieldedHistoryRowId] back into its key, or null when [rowId] is
 * not a well-formed shielded row id (wrong prefix, non-numeric or negative
 * account, entry id not 64 lowercase hex chars).
 */
fun parseShieldedHistoryRowId(rowId: String): ShieldedActivityRowKey? {
    if (!isShieldedActivityRowId(rowId)) return null
    val parts = rowId.removePrefix(SHIELDED_HISTORY_ROW_PREFIX).split(':')
    if (parts.size != 2) return null
    val account = parts[0].takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull() ?: return null
    if (!ENTRY_ID_HEX.matches(parts[1])) return null
    return ShieldedActivityRowKey(account, parts[1])
}

/** How one surfaced kind renders. [outgoing] = it lowers the Home total by principal + fee. */
private data class ShieldedKindSpec(
    @StringRes val rowTitleRes: Int,
    @StringRes val detailTitleRes: Int,
    val iconType: Int,
    val iconBgType: Int,
    val outgoing: Boolean,
    @StringRes val fromBalanceRes: Int?,
    @StringRes val toBalanceRes: Int?
)

/**
 * The kinds that get a Home row. Absent on purpose: 1 ShieldFromAssetLock and
 * 5 Withdrawal (their Core tx is the row — see the de-dup rule above), 7
 * ShieldedSpend (own spend with only self-change, fee unknown: no honest
 * amount) and any tag this app does not know.
 */
private fun kindSpec(kindTag: Int): ShieldedKindSpec? = when (kindTag) {
    ShieldedActivityKindTag.SHIELD -> ShieldedKindSpec(
        R.string.transaction_row_shielded, R.string.transaction_row_shielded,
        TxDisplayCacheEntry.ICON_INTERNAL, TxDisplayCacheEntry.BG_SENT, outgoing = false,
        R.string.external_send_balance_name_platform, R.string.external_send_balance_name_shielded
    )
    ShieldedActivityKindTag.RECEIVED -> ShieldedKindSpec(
        R.string.transaction_row_status_received, R.string.transaction_details_amount_received,
        TxDisplayCacheEntry.ICON_RECEIVED, TxDisplayCacheEntry.BG_RECEIVED, outgoing = false,
        null, R.string.external_send_balance_name_shielded
    )
    ShieldedActivityKindTag.SENT -> ShieldedKindSpec(
        R.string.transaction_row_status_sent, R.string.transaction_details_amount_sent,
        TxDisplayCacheEntry.ICON_SENT, TxDisplayCacheEntry.BG_SENT, outgoing = true,
        R.string.external_send_balance_name_shielded, null
    )
    ShieldedActivityKindTag.UNSHIELD -> ShieldedKindSpec(
        R.string.transaction_row_unshielded, R.string.transaction_row_unshielded,
        TxDisplayCacheEntry.ICON_INTERNAL, TxDisplayCacheEntry.BG_SENT, outgoing = true,
        R.string.external_send_balance_name_shielded, R.string.external_send_balance_name_platform
    )
    ShieldedActivityKindTag.IDENTITY_CREATE -> ShieldedKindSpec(
        R.string.dashpay_upgrade_fee, R.string.dashpay_upgrade_fee,
        TxDisplayCacheEntry.ICON_SENT, TxDisplayCacheEntry.BG_SENT, outgoing = true,
        R.string.external_send_balance_name_shielded, R.string.external_send_balance_name_identity
    )
    ShieldedActivityKindTag.SHIELD_FROM_IDENTITY -> ShieldedKindSpec(
        R.string.transaction_row_shielded, R.string.transaction_row_shielded,
        TxDisplayCacheEntry.ICON_INTERNAL, TxDisplayCacheEntry.BG_SENT, outgoing = false,
        R.string.external_send_balance_name_identity, R.string.external_send_balance_name_shielded
    )
    ShieldedActivityKindTag.IDENTITY_TOP_UP -> ShieldedKindSpec(
        R.string.dashpay_topup_fee, R.string.dashpay_topup_fee,
        TxDisplayCacheEntry.ICON_SENT, TxDisplayCacheEntry.BG_SENT, outgoing = true,
        R.string.external_send_balance_name_shielded, R.string.external_send_balance_name_identity
    )
    else -> null
}

/** Why [planShieldedHistoryRow] produced no row (value-free, loggable). */
enum class ShieldedRowSkip {
    /** De-dup (Core tx row), no honest amount, or unknown kind — by design. */
    KIND_NOT_SHOWN,

    /** An outgoing kind without a known fee: the total would be understated. */
    FEE_UNKNOWN,

    /** The credit arithmetic overflowed. */
    OVERFLOW
}

/** Result of planning one entry: a row, or the reason there is none. */
sealed class ShieldedRowPlan {
    data class Row(val entry: TxDisplayCacheEntry) : ShieldedRowPlan()
    data class Skipped(val reason: ShieldedRowSkip) : ShieldedRowPlan()
}

/**
 * The signed effect of [entry] on the Home total, in duffs: +principal for an
 * incoming kind, −(principal + fee) for an outgoing one, summed in credits
 * and THEN floored (2_796_148_800 + 203_851_200 credits = 3_000_000 duffs,
 * where flooring each part first would lose a duff). Null when an outgoing
 * kind's fee is unknown or the sum overflows.
 */
private fun signedTotalDuffs(entry: ShieldedActivityEntry, outgoing: Boolean): Pair<Long?, ShieldedRowSkip?> {
    if (!outgoing) return creditsToDash(entry.amountCredits).duffs to null
    val fee = entry.feeCredits ?: return null to ShieldedRowSkip.FEE_UNKNOWN
    val total = try {
        Math.addExact(entry.amountCredits, fee)
    } catch (e: ArithmeticException) {
        return null to ShieldedRowSkip.OVERFLOW
    }
    return -creditsToDash(total).duffs to null
}

/**
 * Plan the Home history row for one shielded activity entry — pure.
 *
 * Fail closed: an outgoing kind whose fee is unknown gets NO row (a row must
 * not show a wrong amount), as does an overflowing sum. Pending and Unknown
 * entries render as in flight ("Sending" for a send, otherwise a "Processing"
 * status); Failed entries never reach here (the mapping drops them, so the
 * sync deletes their row on its next pass).
 */
internal fun planShieldedHistoryRowOrSkip(
    entry: ShieldedActivityEntry,
    resolveString: (Int) -> String
): ShieldedRowPlan {
    val spec = kindSpec(entry.kindTag) ?: return ShieldedRowPlan.Skipped(ShieldedRowSkip.KIND_NOT_SHOWN)
    val (value, skip) = signedTotalDuffs(entry, spec.outgoing)
    if (value == null) return ShieldedRowPlan.Skipped(skip ?: ShieldedRowSkip.OVERFLOW)
    val inFlight = entry.status != ShieldedActivityStatus.CONFIRMED
    val isSend = entry.kindTag == ShieldedActivityKindTag.SENT
    val titleRes = if (isSend && inFlight) R.string.transaction_row_status_sending else spec.rowTitleRes
    val statusText = if (inFlight && !isSend) resolveString(R.string.transaction_row_status_processing) else ""
    return ShieldedRowPlan.Row(
        TxDisplayCacheEntry(
            rowId = shieldedHistoryRowId(entry.accountIndex, entry.id),
            title = resolveString(titleRes),
            valueSatoshis = value,
            iconType = spec.iconType,
            iconBgType = spec.iconBgType,
            statusText = statusText,
            comment = "",
            transactionAmount = 1,
            time = entry.timestampMs,
            hasErrors = false,
            service = null,
            swapStatus = null,
            exchangeRateFiatCode = null,
            exchangeRateFiatValue = null,
            contactUsername = null,
            contactDisplayName = null,
            contactAvatarUrl = null,
            contactUserId = null,
            filterFlags = if (spec.outgoing) TxDisplayCacheEntry.FLAG_SENT else TxDisplayCacheEntry.FLAG_RECEIVED,
            customIconId = null,
            source = TxDisplayCacheEntry.SOURCE_SHIELDED
        )
    )
}

/** [planShieldedHistoryRowOrSkip] without the skip reason. */
internal fun planShieldedHistoryRow(
    entry: ShieldedActivityEntry,
    resolveString: (Int) -> String
): TxDisplayCacheEntry? = (planShieldedHistoryRowOrSkip(entry, resolveString) as? ShieldedRowPlan.Row)?.entry

/** Every row planned from one activity snapshot, plus value-free skip counts for the log. */
internal data class ShieldedHistoryPlan(
    val rows: List<TxDisplayCacheEntry>,
    val skipped: Map<ShieldedRowSkip, Int>
)

/** Plan all rows of one activity snapshot (pure). Duplicate keys keep the first entry. */
internal fun planShieldedHistoryRows(
    entries: List<ShieldedActivityEntry>,
    resolveString: (Int) -> String
): ShieldedHistoryPlan {
    val rows = LinkedHashMap<String, TxDisplayCacheEntry>()
    val skipped = HashMap<ShieldedRowSkip, Int>()
    for (entry in entries) {
        when (val plan = planShieldedHistoryRowOrSkip(entry, resolveString)) {
            is ShieldedRowPlan.Row -> rows.putIfAbsent(plan.entry.rowId, plan.entry)
            is ShieldedRowPlan.Skipped -> skipped.merge(plan.reason, 1, Int::plus)
        }
    }
    return ShieldedHistoryPlan(rows.values.toList(), skipped)
}

// ── Detail sheet model ────────────────────────────────────────────────

/**
 * What the transaction-detail sheet shows for a shielded-activity row
 * (`TransactionResultViewBinder.bindPlatformActivityDetail`). Amounts are
 * duffs floored from credits; the principal and the fee are floored
 * separately, so they are shown as recorded rather than as the Home row's
 * exact sum.
 *
 * @param principalDuffs |principal| (fees excluded).
 * @param outgoing true when the entry lowered the Home total ("−" sign).
 * @param feeDuffs the fee, or null when the SDK recorded none.
 * @param fromBalanceRes / [toBalanceRes] balance names for the From / To
 *   rows; null where the counterparty is external (another wallet).
 */
data class PlatformActivityDetail(
    @StringRes val titleRes: Int,
    val iconType: Int,
    val principalDuffs: Long,
    val outgoing: Boolean,
    val feeDuffs: Long?,
    val timestampMs: Long,
    val pending: Boolean,
    val memo: String?,
    @StringRes val fromBalanceRes: Int?,
    @StringRes val toBalanceRes: Int?
) {
    /** Value-free: a detail carries amounts and a memo, which must never reach a log. */
    override fun toString(): String = "PlatformActivityDetail(titleRes=$titleRes, pending=$pending)"
}

/**
 * Map an entry to its detail-sheet model, or null for every kind that has no
 * Home row (the same rules as [planShieldedHistoryRowOrSkip], including the
 * fee-unknown and overflow fail-closed cases) — a sheet is never built from
 * data the row itself would not show.
 */
fun platformActivityDetailOf(entry: ShieldedActivityEntry): PlatformActivityDetail? {
    val spec = kindSpec(entry.kindTag) ?: return null
    if (signedTotalDuffs(entry, spec.outgoing).first == null) return null
    return PlatformActivityDetail(
        titleRes = spec.detailTitleRes,
        iconType = spec.iconType,
        principalDuffs = creditsToDash(entry.amountCredits).duffs,
        outgoing = spec.outgoing,
        feeDuffs = entry.feeCredits?.let { creditsToDash(it).duffs },
        timestampMs = entry.timestampMs,
        pending = entry.status != ShieldedActivityStatus.CONFIRMED,
        memo = entry.memo,
        fromBalanceRes = spec.fromBalanceRes,
        toBalanceRes = spec.toBalanceRes
    )
}
