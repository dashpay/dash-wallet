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
// De-dup rule: kinds whose operation IS a wallet Core transaction already
// render as an L1 row, so they get no shielded row (two rows for one
// operation would double-count it). ShieldFromAssetLock (1, Core asset lock →
// pool) always does; Withdrawal (5, pool → Core) does only when it pays one
// of the wallet's OWN Core addresses (the L1 "Unshielded" row). A withdrawal
// to a FOREIGN Core address never touches the wallet's L1, so it renders
// here as a "Sent" row; one whose destination cannot be resolved gets no row
// (a gap is better than a double count). Ids could not collide anyway:
// shielded ids carry the `shielded:` prefix, L1 ids are txid hex or group ids.
//
// Ownership ([CounterpartyOwnership], resolved app-side per entry against
// the SDK store) only matters for kinds whose counterparty may be someone
// else: Unshield (4, a Platform address), Withdrawal (5, a Core script) and
// IdentityTopUp (9, an identity). Shield (0), IdentityCreate (6) and
// ShieldFromIdentity (8) are own by construction — the wallet signed both
// ends (its own Platform addresses / the identity it creates / its own
// identity) — and Received (2) / Sent (3) are external by construction.

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

/**
 * Whether an entry's counterparty belongs to the active wallet. [UNKNOWN]
 * (undecodable counterparty, failed lookup, no network parameters) is never
 * treated as either answer where the answer changes the row.
 */
enum class CounterpartyOwnership { OWN, EXTERNAL, UNKNOWN }

/** The Base58 version bytes of the active network's Core addresses. */
data class CoreAddressVersions(val pubKeyHash: Int, val scriptHash: Int)

/** What has to be looked up to resolve one entry's [CounterpartyOwnership]. */
sealed class CounterpartyQuery {
    /** The kind's ownership is fixed (or irrelevant): no lookup. */
    object NotNeeded : CounterpartyQuery()

    /** The counterparty could not be decoded: the ownership is [CounterpartyOwnership.UNKNOWN]. */
    object Undecodable : CounterpartyQuery()

    /** A Core address (Base58, this network) — look it up in the SDK's `core_addresses`. */
    class CoreAddress(val address: String) : CounterpartyQuery() {
        override fun toString() = "CoreAddress(…)"
    }

    /** A Platform address hash (20 bytes) — look it up in the wallet's `platform_addresses`. */
    class PlatformAddressHash(val hash: ByteArray) : CounterpartyQuery() {
        override fun toString() = "PlatformAddressHash(…)"
    }

    /** An identity id (32 bytes) — look it up in `identities`. */
    class Identity(val identityId: ByteArray) : CounterpartyQuery() {
        override fun toString() = "Identity(…)"
    }
}

private const val HASH160_BYTES = 20
private const val IDENTITY_ID_BYTES = 32

/**
 * Decode a Core output script to its Base58 address on the network given by
 * [versions]: P2PKH (`76 a9 14 <20> 88 ac`) or P2SH (`a9 14 <20> 87`) only;
 * anything else (and a null [versions]) is null. Pure.
 */
internal fun coreScriptToAddress(script: ByteArray, versions: CoreAddressVersions?): String? {
    if (versions == null) return null
    val op = { i: Int -> script[i].toInt() and 0xff }
    return when {
        script.size == 25 && op(0) == 0x76 && op(1) == 0xa9 && op(2) == HASH160_BYTES &&
            op(23) == 0x88 && op(24) == 0xac ->
            org.bitcoinj.core.Base58.encodeChecked(versions.pubKeyHash, script.copyOfRange(3, 23))
        script.size == 23 && op(0) == 0xa9 && op(1) == HASH160_BYTES && op(22) == 0x87 ->
            org.bitcoinj.core.Base58.encodeChecked(versions.scriptHash, script.copyOfRange(2, 22))
        else -> null
    }
}

/**
 * The 20-byte hash of a serialized Platform address (`PlatformAddress::to_bytes`
 * in rs-dpp: one storage type byte — 0x00 P2PKH, 0x01 P2SH — then the hash),
 * or null for any other shape. Pure.
 */
internal fun platformAddressHashOf(counterparty: ByteArray): ByteArray? {
    if (counterparty.size != 1 + HASH160_BYTES) return null
    val type = counterparty[0].toInt() and 0xff
    if (type != 0x00 && type != 0x01) return null
    return counterparty.copyOfRange(1, 1 + HASH160_BYTES)
}

/** What [entry] needs looked up to know whose its counterparty is. Pure. */
internal fun counterpartyQueryFor(entry: ShieldedActivityEntry, versions: CoreAddressVersions?): CounterpartyQuery =
    when (entry.kindTag) {
        ShieldedActivityKindTag.UNSHIELD ->
            platformAddressHashOf(entry.counterparty)?.let { CounterpartyQuery.PlatformAddressHash(it) }
                ?: CounterpartyQuery.Undecodable
        ShieldedActivityKindTag.WITHDRAWAL ->
            coreScriptToAddress(entry.counterparty, versions)?.let { CounterpartyQuery.CoreAddress(it) }
                ?: CounterpartyQuery.Undecodable
        ShieldedActivityKindTag.IDENTITY_TOP_UP ->
            entry.identityId.takeIf { it.size == IDENTITY_ID_BYTES }?.let { CounterpartyQuery.Identity(it.copyOf()) }
                ?: CounterpartyQuery.Undecodable
        else -> CounterpartyQuery.NotNeeded
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
 * A pool spend to someone else (or to a destination whose owner is unknown,
 * where only the sign is certain): a neutral "Sent" with no "To" balance.
 */
private val SENT_TO_OTHER = ShieldedKindSpec(
    R.string.transaction_row_status_sent, R.string.transaction_details_amount_sent,
    TxDisplayCacheEntry.ICON_SENT, TxDisplayCacheEntry.BG_SENT, outgoing = true,
    R.string.external_send_balance_name_shielded, null
)

/**
 * How [kindTag] renders given its counterparty's [ownership], or null for no
 * row: 1 ShieldFromAssetLock and an OWN 5 Withdrawal (their Core tx is the
 * row — see the de-dup rule above), a 5 Withdrawal of UNKNOWN destination,
 * 7 ShieldedSpend (own spend with only self-change, fee unknown: no honest
 * amount) and any tag this app does not know. An Unshield (4) or identity
 * top-up (9) that is not provably OWN renders as [SENT_TO_OTHER] — its value
 * is the same either way, only the internal-move label would be wrong.
 */
private fun kindSpec(kindTag: Int, ownership: CounterpartyOwnership): ShieldedKindSpec? = when (kindTag) {
    ShieldedActivityKindTag.UNSHIELD -> if (ownership == CounterpartyOwnership.OWN) unshieldToOwn else SENT_TO_OTHER
    ShieldedActivityKindTag.IDENTITY_TOP_UP -> if (ownership == CounterpartyOwnership.OWN) topUpOwn else SENT_TO_OTHER
    ShieldedActivityKindTag.WITHDRAWAL -> if (ownership == CounterpartyOwnership.EXTERNAL) SENT_TO_OTHER else null
    else -> fixedKindSpec(kindTag)
}

private val unshieldToOwn = ShieldedKindSpec(
    R.string.transaction_row_unshielded, R.string.transaction_row_unshielded,
    TxDisplayCacheEntry.ICON_INTERNAL, TxDisplayCacheEntry.BG_SENT, outgoing = true,
    R.string.external_send_balance_name_shielded, R.string.external_send_balance_name_platform
)

private val topUpOwn = ShieldedKindSpec(
    R.string.dashpay_topup_fee, R.string.dashpay_topup_fee,
    TxDisplayCacheEntry.ICON_SENT, TxDisplayCacheEntry.BG_SENT, outgoing = true,
    R.string.external_send_balance_name_shielded, R.string.external_send_balance_name_identity
)

/** The kinds whose rendering does not depend on ownership. */
private fun fixedKindSpec(kindTag: Int): ShieldedKindSpec? = when (kindTag) {
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
    ShieldedActivityKindTag.SENT -> SENT_TO_OTHER
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
    else -> null
}

/** Why [planShieldedHistoryRow] produced no row (value-free, loggable). */
enum class ShieldedRowSkip {
    /** De-dup (Core tx row), no honest amount, or unknown kind — by design. */
    KIND_NOT_SHOWN,

    /** An outgoing kind without a known fee: the total would be understated. */
    FEE_UNKNOWN,

    /** The credit arithmetic overflowed. */
    OVERFLOW,

    /** A withdrawal whose destination's owner is unknown: skipped rather than risk a double count. */
    OWNERSHIP_UNKNOWN
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
    ownership: CounterpartyOwnership,
    resolveString: (Int) -> String
): ShieldedRowPlan {
    val spec = kindSpec(entry.kindTag, ownership)
        ?: return ShieldedRowPlan.Skipped(
            if (entry.kindTag == ShieldedActivityKindTag.WITHDRAWAL && ownership == CounterpartyOwnership.UNKNOWN) {
                ShieldedRowSkip.OWNERSHIP_UNKNOWN
            } else {
                ShieldedRowSkip.KIND_NOT_SHOWN
            }
        )
    val (value, skip) = signedTotalDuffs(entry, spec.outgoing)
    if (value == null) return ShieldedRowPlan.Skipped(skip ?: ShieldedRowSkip.OVERFLOW)
    val inFlight = entry.status != ShieldedActivityStatus.CONFIRMED
    val isSend = spec === SENT_TO_OTHER
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
    ownership: CounterpartyOwnership,
    resolveString: (Int) -> String
): TxDisplayCacheEntry? =
    (planShieldedHistoryRowOrSkip(entry, ownership, resolveString) as? ShieldedRowPlan.Row)?.entry

/** Every row planned from one activity snapshot, plus value-free skip counts for the log. */
internal data class ShieldedHistoryPlan(
    val rows: List<TxDisplayCacheEntry>,
    val skipped: Map<ShieldedRowSkip, Int>
)

/**
 * Plan all rows of one activity snapshot (pure). [ownership] is each entry's
 * resolved [CounterpartyOwnership] (an entry it does not answer for is
 * [CounterpartyOwnership.UNKNOWN]). Duplicate keys keep the first entry.
 */
internal fun planShieldedHistoryRows(
    entries: List<ShieldedActivityEntry>,
    ownership: (ShieldedActivityEntry) -> CounterpartyOwnership,
    resolveString: (Int) -> String
): ShieldedHistoryPlan {
    val rows = LinkedHashMap<String, TxDisplayCacheEntry>()
    val skipped = HashMap<ShieldedRowSkip, Int>()
    for (entry in entries) {
        when (val plan = planShieldedHistoryRowOrSkip(entry, ownership(entry), resolveString)) {
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
 * Map an entry to its detail-sheet model, or null for every entry that has no
 * Home row (the same rules and the same [ownership] as
 * [planShieldedHistoryRowOrSkip], including the fee-unknown and overflow
 * fail-closed cases) — a sheet is never built from data the row would not show.
 */
fun platformActivityDetailOf(entry: ShieldedActivityEntry, ownership: CounterpartyOwnership): PlatformActivityDetail? {
    val spec = kindSpec(entry.kindTag, ownership) ?: return null
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
