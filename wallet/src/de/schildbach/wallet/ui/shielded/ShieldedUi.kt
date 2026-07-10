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

package de.schildbach.wallet.ui.shielded

import org.bitcoinj.core.Coin
import org.bitcoinj.utils.Fiat
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.util.toFormattedString
import java.math.BigDecimal
import java.text.NumberFormat

/**
 * Shared UI model for the shielded-balances screens (Figma canvas 231:200
 * "Payments"). All screens sit on
 * [de.schildbach.wallet.service.platform.sdk.ShieldedBalanceService] behind
 * the `USE_KOTLIN_SDK_SHIELDED` flag.
 */

/** Direction of the "Internal transfer" flow (Figma 1746:18463 / 1746:18480). */
enum class ShieldedTransferDirection {
    /**
     * Dash Wallet → Shielded balance: spends the L1 balance via an asset
     * lock + Type 18 `ShieldFromAssetLock`
     * ([de.schildbach.wallet.service.platform.sdk.ShieldedBalanceService.shieldFromWallet]).
     */
    ToShielded,

    /** Shielded balance → Dash Wallet (up to ~10 min; Type 19 withdraw to Core). */
    FromShielded
}

/**
 * The write-contract UI states for a shielded spend, mirroring the
 * `SdkWriteResult` partition. [MayHaveGoneThrough] (from
 * `SdkWriteResult.Ambiguous`) is TERMINAL: the transfer may already be on
 * chain, so the UI must never offer a retry from that state.
 */
sealed class ShieldedSubmitState {
    object Idle : ShieldedSubmitState()

    /** Blocking Halo 2 proof + broadcast in flight (~30s, indeterminate). */
    object Proving : ShieldedSubmitState()

    /** `SdkWriteResult.Broadcast` — show success and leave the flow. */
    object Success : ShieldedSubmitState()

    /** `SdkWriteResult.NotBroadcast` — provably nothing was sent; retry is safe. */
    data class NotSent(val reason: String) : ShieldedSubmitState()

    /** `SdkWriteResult.Ambiguous` — terminal "may have gone through, do NOT retry". */
    object MayHaveGoneThrough : ShieldedSubmitState()

    /**
     * Dash Wallet → Shielded only: the L1 asset lock is out (the Dash
     * left the spendable balance) but the shield transition needs an
     * automatic retry (`ShieldFromWalletOutcome.SHIELD_PENDING_RETRY`).
     * TERMINAL like [MayHaveGoneThrough] — the funds are committed, so
     * the UI must never offer a manual "send again".
     */
    object LockedPendingShield : ShieldedSubmitState()

    /**
     * The stall watchdog fired: no terminal result within
     * [de.schildbach.wallet.ui.shielded.ShieldedTransferExecutor.STALL_TIMEOUT_MS]
     * (live incident: a Rust FFI deadlock kept the spend RUNNABLE inside
     * an uncancellable JNI frame for 11+ minutes). Funds-honest — we do
     * NOT know whether anything broadcast — so it is treated as
     * IN-FLIGHT for the no-resubmit rule (`canContinue` stays false; a
     * retry while the wedged call is still alive could double-submit
     * once the wedge clears) and it is sticky like [MayHaveGoneThrough]:
     * a fresh screen visit keeps it. [acknowledged] only hides the
     * on-screen overlay; the state itself is superseded only by a REAL
     * terminal outcome (the wedged call eventually returning) or a
     * process restart.
     */
    data class Stalled(val acknowledged: Boolean = false) : ShieldedSubmitState()
}

/** Platform credits per duff (1 DASH = 1e8 duffs = 1e11 credits). */
private const val CREDITS_PER_DUFF = 1_000L

/**
 * The amount in Platform credits, formatted with grouping separators, e.g. "100,000,000,000".
 * No overflow: max money (2.1e15 duffs) × 1000 stays well below Long.MAX_VALUE.
 */
fun Dash.toCreditsString(): String =
    NumberFormat.getIntegerInstance().format(duffs * CREDITS_PER_DUFF)

/**
 * Compact credits amount with a magnitude suffix, e.g. `115.5B` — used on the
 * More-screen "Shielded" balance card (Figma 1693:15853 shows "115.5ᴮ C").
 * One decimal, trailing ".0" trimmed.
 */
fun Dash.toCompactCreditsString(): String {
    val credits = duffs * CREDITS_PER_DUFF
    val (divisor, suffix) = when {
        credits >= 1_000_000_000_000L -> 1_000_000_000_000L to "T"
        credits >= 1_000_000_000L -> 1_000_000_000L to "B"
        credits >= 1_000_000L -> 1_000_000L to "M"
        credits >= 1_000L -> 1_000L to "K"
        else -> 1L to ""
    }
    val scaled = credits.toDouble() / divisor
    val text = String.format(java.util.Locale.US, "%.1f", scaled).removeSuffix(".0")
    return "$text$suffix"
}

private val DASH_FORMAT: MonetaryFormat = MonetaryFormat.BTC
    .minDecimals(2)
    .repeatOptionalDecimals(1, 6)
    .noCode()

/** "3.00"-style Dash string (min 2, max 8 decimals), matching the designs. */
fun Dash.toDisplayString(): String = DASH_FORMAT.format(Coin.valueOf(duffs)).toString()

/** Plain decimal string with trailing zeros trimmed — used to seed the keypad text. */
fun Dash.toKeypadString(): String {
    if (isZero) return "0"
    return toBigDecimal().stripTrailingZeros().toPlainString()
}

/** Parses keypad text as a Dash amount; null when unparseable or negative. */
fun parseDashOrNull(text: String): Dash? = try {
    val value = Dash.parse(text.ifBlank { "0" }.trimEnd('.'))
    if (value.isNegative) null else value
} catch (e: Exception) {
    null
}

/** Parses keypad text as a plain decimal; null when unparseable or negative. */
fun parseDecimalOrNull(text: String): BigDecimal? = try {
    val value = BigDecimal(text.ifBlank { "0" }.trimEnd('.'))
    if (value.signum() < 0) null else value
} catch (e: Exception) {
    null
}

/** Dash → fiat at [rate] (fiat value of 1 DASH); null when no rate. */
fun Dash.toFiatAt(rate: Fiat?): Fiat? = rate?.let {
    org.bitcoinj.utils.ExchangeRate(Coin.COIN, it).coinToFiat(Coin.valueOf(duffs))
}

/** Fiat → Dash at [rate] (fiat value of 1 DASH); null when no rate. */
fun Fiat.toDashAt(rate: Fiat?): Dash? = rate?.let {
    Dash(org.bitcoinj.utils.ExchangeRate(Coin.COIN, it).fiatToCoin(this).value)
}

/** "$50.00"-style formatted fiat string. */
fun Fiat.toDisplay(): String = toFormattedString()

/** Ellipsized middle of a long address for one-line display. */
fun String.ellipsizeAddress(head: Int = 10, tail: Int = 6): String =
    if (length <= head + tail + 1) this else "${take(head)}…${takeLast(tail)}"
