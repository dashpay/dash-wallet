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
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet_test.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Address
import org.dash.wallet.common.money.Dash
import org.dashfoundation.dashsdk.errors.DashSdkError
import org.dashfoundation.dashsdk.errors.mapNativeErrors
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

// ── Pure classification (host-testable) ───────────────────────────────

/**
 * The `PlatformWalletFFIResultCode` values the core-send classification
 * below needs to recognize on [DashSdkError.PlatformWallet.Generic] — the
 * codes without a dedicated Kotlin type. Values from
 * `rs-platform-wallet-ffi/src/error.rs`.
 */
internal const val PWFFI_ERROR_INVALID_PARAMETER = 2
internal const val PWFFI_ERROR_UNKNOWN = 99

// `AddressPoolTypeTagFFI` discriminant for the SDK Room `CoreAddressEntity`
// rows: 0 = External (receive chain). The account-tag pair for BIP44
// account 0 lives with the consolidation helpers below
// ([ACCOUNT_TYPE_TAG_STANDARD] / [STANDARD_ACCOUNT_TAG_BIP44]).
internal const val ADDRESS_POOL_TAG_EXTERNAL = 0

/**
 * No-double-broadcast decision table for a throwable raised by the SDK's
 * CORE send pipeline ([SdkL1SendSource.sendToAddress] →
 * `ManagedPlatformWallet.sendToAddresses`: builder new → addOutput →
 * setFunding → buildSigned → broadcast). Layered ON TOP of the shared
 * [classifyBroadcastFailure] table with three send-specific rules, each
 * traced through the FFI sources
 * (`rs-platform-wallet-ffi/src/core_wallet/{transaction_builder,broadcast}.rs`):
 *
 * 1. **Build/funding failures are pre-broadcast.**
 *    `core_wallet_tx_builder_set_funding` and `..._build_signed` wrap every
 *    internal failure as `ErrorWalletOperation` (code 6 →
 *    [DashSdkError.PlatformWallet.WalletOperation]) with the stable message
 *    prefixes `"set_funding failed: …"` / `"transaction build failed: …"`.
 *    Both steps run strictly before any bytes reach a peer — this is where
 *    INSUFFICIENT FUNDS surfaces (key-wallet's coin selection inside
 *    `build_signed`), so an SDK-side shortfall falls back to dashj, which
 *    then raises its own `InsufficientMoneyException` exactly as before.
 *    No other step of this flow produces `WalletOperation`, but the match
 *    still requires the message prefix rather than the bare type.
 *
 * 2. **Builder input validation is pre-broadcast.**
 *    `core_wallet_tx_builder_add_output` / network-mismatch checks return
 *    `ErrorInvalidParameter` (platform-wallet code 2, which has no
 *    dedicated Kotlin type → [DashSdkError.PlatformWallet.Generic] with
 *    [DashSdkError.PlatformWallet.Generic.nativeCode] 2). Within this flow
 *    code 2 is only ever produced by the pre-funding builder steps.
 *
 * 3. **A DEFINITIVE broadcast rejection is pre-network by contract.**
 *    `TransactionBroadcaster` implementations must return
 *    `BroadcastError::Rejected` "**only** when the transaction provably
 *    never reached the network (no bytes handed to any peer or endpoint)"
 *    (`rs-platform-wallet/src/broadcaster.rs`), and the reserved inputs are
 *    released before the error propagates
 *    (`broadcast_releasing_on_rejection`). It crosses the FFI as
 *    `PlatformWalletError::TransactionBroadcast` → `ErrorUnknown` (99) with
 *    the typed Display prefix `"Transaction broadcast failed: …"` — matched
 *    on code AND prefix because 99 is also the generic fallthrough code.
 *
 * TYPED SUPERSESSION (v41int14, dashpay/platform#4247/#4256): the failures
 * rules 1–3 message-match arrive from the v41int14 AAR as DEDICATED Kotlin
 * types instead of WalletOperation/Generic(99) + message prefix —
 * [DashSdkError.PlatformWallet.TransactionBuild] (code 32, the request is
 * at fault: bad fundingPath, monetary-bound breach, malformed recipients),
 * [DashSdkError.PlatformWallet.TransactionSigning] (code 33, the request is
 * FINE: only the signatures failed, reservation released natively —
 * retryable once the signer is unlocked, keyed by
 * [L1_SIGNER_LOCKED_REASON]) and
 * [DashSdkError.PlatformWallet.TransactionBroadcastRejected] (code 26, the
 * DEFINITIVE counterpart of code 20: not on the network, never will be,
 * reservation released). All three are definitively pre-network by the SDK
 * contract, so they classify NotBroadcast by TYPE — without these arms they
 * would fall through the message-prefix rules to [classifyBroadcastFailure]
 * and come back Ambiguous (a plain build refusal shown as "may be on the
 * network", blocking the safe dashj fallback). The message-prefix rules
 * below stay for the transition window.
 *
 * Everything else defers to [classifyBroadcastFailure]; notably
 * [DashSdkError.PlatformWallet.TransactionBroadcastUnconfirmed] (code 20 —
 * the SDK's own "may already be on the network, inputs stay reserved, do
 * NOT retry") and any transport/unknown error stay
 * [SdkWriteResult.Ambiguous]. Falling back to dashj on an ambiguous
 * outcome would be a potential DOUBLE PAY, not just a double broadcast:
 * dashj's coin selection may pick different UTXOs than the SDK tx, so both
 * transactions could confirm.
 *
 * KNOWN LOW (accepted, documented): a failure classified NotBroadcast here
 * can still have happened AFTER a successful `buildSigned` but BEFORE the
 * broadcast call (e.g. a `coreWallet()` handle failure in
 * [CoreSendAllNative]) — in that window the build's engine-side UTXO
 * reservation is NOT released and leaks until its TTL expires. No funds
 * move and nothing was broadcast (the classification is correct); the cost
 * is that the reserved inputs are unavailable to new SDK builds for the
 * TTL window.
 */
internal fun classifyCoreSendFailure(t: Throwable): SdkWriteResult<Nothing> = when {
    // Typed selector shortfall (platform-wallet code 22, v41int11's atomic
    // send + single-account payment paths). Raised strictly while BUILDING —
    // `map_builder_error` in rs-platform-wallet `wallet/core/transaction.rs`
    // (atomic finalize) and `PaymentInsufficientFunds` from
    // `build_signed_payment` are the only producers, both pre-broadcast, so
    // nothing reached the wire. Without this arm the typed error (whose
    // message "insufficient unreserved Core funds …" matches no substring
    // rule below) fell through to Ambiguous, which would surface a plain
    // insufficient-funds as a "may be on the network" error and block the
    // dashj fallback that raises the usual InsufficientMoneyException.
    t is DashSdkError.PlatformWallet.CoreInsufficientFunds ->
        SdkWriteResult.NotBroadcast(
            "core send failed pre-broadcast (funding-account shortfall): ${t.message}", t
        )
    // Typed build refusal (code 32, v41int14). Every `buildSignedPayment` /
    // builder rejection that is neither a shortfall nor a signing failure:
    // the REQUEST is at fault (a verbatim retry fails identically) and the
    // rejection is strictly pre-broadcast — the tx was never assembled or
    // never left the builder. Supersedes the WalletOperation message-prefix
    // rule below for new AARs.
    t is DashSdkError.PlatformWallet.TransactionBuild ->
        SdkWriteResult.NotBroadcast("core send failed pre-broadcast (transaction build): ${t.message}", t)
    // Typed signing failure (code 33, v41int14). The request was VALID and
    // the tx fully assembled — only the input signatures could not be
    // produced (Keystore/Keychain mnemonic locked or missing). The native
    // layer released the build's input reservation before returning, so
    // nothing reached the wire and the IDENTICAL send may be resubmitted
    // once the signer is unlocked. Keyed by [L1_SIGNER_LOCKED_REASON] so the
    // send UI surfaces "unlock to continue", never a hard payment failure
    // (dashpay/platform#4256).
    t is DashSdkError.PlatformWallet.TransactionSigning ->
        SdkWriteResult.NotBroadcast("$L1_SIGNER_LOCKED_REASON — retryable after unlock: ${t.message}", t)
    // Typed definitive broadcast rejection (code 26, v41int14) — the
    // DEFINITIVE counterpart of TransactionBroadcastUnconfirmed (20): Core
    // provably rejected the tx, it is not on the network and will not get
    // there, and the UTXO reservation (and any deferred token) was already
    // released. Safe to re-plan or fall back. Supersedes the Generic(99) +
    // "Transaction broadcast failed" prefix rule below for new AARs.
    t is DashSdkError.PlatformWallet.TransactionBroadcastRejected ->
        SdkWriteResult.NotBroadcast(
            "core send definitively rejected before reaching the network: ${t.message}", t
        )
    t is DashSdkError.PlatformWallet.WalletOperation &&
        (t.message?.startsWith("set_funding failed") == true ||
            t.message?.startsWith("transaction build failed") == true) ->
        SdkWriteResult.NotBroadcast("core send failed pre-broadcast (build/funding): ${t.message}", t)
    t is DashSdkError.PlatformWallet.Generic &&
        t.nativeCode == PWFFI_ERROR_INVALID_PARAMETER ->
        SdkWriteResult.NotBroadcast("core send failed pre-broadcast (builder validation): ${t.message}", t)
    t is DashSdkError.PlatformWallet.Generic &&
        t.nativeCode == PWFFI_ERROR_UNKNOWN &&
        t.message?.startsWith("Transaction broadcast failed") == true ->
        SdkWriteResult.NotBroadcast(
            "core send definitively rejected before reaching the network: ${t.message}", t
        )
    else -> classifyBroadcastFailure(t)
}

/**
 * [classifyCoreSendFailure] specialized for the DEFERRED broadcast step of a
 * BIP70/BIP270 payment ([SdkL1SendSource.broadcastDeferredPayment] →
 * `ManagedPlatformWallet.broadcastSigned`). The reservation-token sibling
 * errors are DEFINITIVE pre-network refusals by contract — the FFI rejects
 * the token (aged out / already consumed / different wallet generation)
 * before touching the broadcaster — so they classify NotBroadcast rather
 * than falling through to the shared table's Ambiguous default. Everything
 * else defers to [classifyCoreSendFailure] unchanged.
 */
internal fun classifyDeferredBroadcastFailure(t: Throwable): SdkWriteResult<Nothing> = when (t) {
    is DashSdkError.PlatformWallet.StaleReservationToken,
    is DashSdkError.PlatformWallet.ReservationTokenConsumed,
    is DashSdkError.PlatformWallet.ReservationWalletMismatch ->
        SdkWriteResult.NotBroadcast(
            "deferred broadcast refused pre-network (${t.javaClass.simpleName}): ${t.message}", t
        )
    else -> classifyCoreSendFailure(t)
}

// ── Send-all (drain) — pure pieces of the iOS-validated max pattern ───

/**
 * Fee reserve backing the send-all floor, in duffs. The drain's fee at the
 * builder's default rate (1000 duffs/kB, `FeeRate::normal()`) is
 * `~44 + 148·n_inputs` duffs, so 10 000 covers a drain of ~67 inputs —
 * far beyond a typical wallet. When a wallet DOES exceed it, the floor
 * attempt fails pre-broadcast with the engine's "Insufficient funds" and
 * [SdkL1SendService] retries once engine-authoritatively (floor 1) — the
 * adjust-down half of the pattern.
 */
internal const val SEND_ALL_FEE_RESERVE_DUFFS = 10_000L

/**
 * The [SdkWriteResult.NotBroadcast] reason prefix for a CLOSED L1 funding
 * gate — the engine is not running or its filter scan has not caught up to
 * the chain tip yet, i.e. the one refusal that genuinely means "the wallet
 * is not synced enough to send". [de.schildbach.wallet.payments.SendCoinsTaskRunner]
 * keys the user-facing "not fully synced" diagnosis off this prefix; every
 * other NotBroadcast reason (flag off, bad address, builder shortfall, …)
 * must NOT be presented as a sync problem.
 */
internal const val L1_FUNDING_GATE_CLOSED_REASON = "L1 funding gate closed"

/**
 * The [SdkWriteResult.NotBroadcast] reason prefix for a signing failure of a
 * fully-assembled Core transaction
 * ([DashSdkError.PlatformWallet.TransactionSigning], native code 33): the
 * REQUEST was valid and the inputs were reserved and released again natively —
 * only the signatures could not be produced, typically because the
 * Keystore/Keychain mnemonic is locked. Retryable-after-unlock by SDK
 * contract ("Surface this as 'unlock to continue', never as 'this payment is
 * invalid'" — dashpay/platform#4256): the caller may resubmit the IDENTICAL
 * send once the signer is usable. [de.schildbach.wallet.payments.sdkSendNotAttemptedException]
 * keys the typed [de.schildbach.wallet.payments.SendSignerLockedException]
 * off this prefix, exactly like [L1_FUNDING_GATE_CLOSED_REASON] keys the
 * not-synced diagnosis — so the UI can say "unlock and try again" instead of
 * a hard payment failure.
 */
internal const val L1_SIGNER_LOCKED_REASON = "L1 signer locked"

/**
 * The deliver-at-least floor for a send-all: `spendable − reserve`,
 * clamped to 1 (the JNI boundary rejects a non-positive output amount).
 * iOS-validated pattern: this is also the max amount a UI should show for
 * "send max" — the engine then delivers `total − fee ≥ floor`, or reports
 * insufficient-at-fee and the caller adjusts down.
 */
internal fun sendAllFloorDuffs(
    spendableDuffs: Long,
    reserveDuffs: Long = SEND_ALL_FEE_RESERVE_DUFFS
): Long = (spendableDuffs - reserveDuffs).coerceAtLeast(1L)

/**
 * True iff [t] is the engine's insufficient-at-fee build failure — the ONE
 * failure the send-all path may retry with a lower floor. By construction
 * a subset of [classifyCoreSendFailure]'s NotBroadcast arm (WalletOperation
 * with the `transaction build failed` FFI prefix), so nothing was
 * broadcast and a single retry cannot double-pay. The "Insufficient funds"
 * text is `BuilderError::InsufficientFunds` / `SelectionError::InsufficientFunds`
 * Display (key-wallet `transaction_builder.rs` / `coin_selection.rs`),
 * stable in the pinned engine.
 */
internal fun isSendAllShortfall(t: Throwable): Boolean =
    t is DashSdkError.PlatformWallet.WalletOperation &&
        t.message?.startsWith("transaction build failed") == true &&
        t.message?.contains("Insufficient funds") == true

// ── DashPay receival-account fallback — pure pieces ───────────────────

/**
 * `AccountTypeTagFFI::DashpayReceivingFunds` — the numeric `typeTag` the
 * account-balance JSON reports for a DashPay receiving-funds account (a
 * contact's payments to us). Value from rs-platform-wallet-ffi
 * `wallet_restore_types.rs` (`DashpayReceivingFunds = 12`), written into
 * the JSON as `e.type_tag as u8` by the JNI bridge's
 * `walletManagerAccountBalances` (rs-unified-sdk-jni `dashpay.rs`).
 */
internal const val ACCOUNT_TYPE_TAG_DASHPAY_RECEIVING_FUNDS = 12

/**
 * Fee headroom required ON TOP of the send amount for a receival account
 * to qualify as the fallback funding source, in duffs. Same sizing
 * rationale as [SEND_ALL_FEE_RESERVE_DUFFS]: at the builder's default rate
 * (1000 duffs/kB, the rate both the normal send and the fallback build
 * use via `feePerKb = 0`) the fee is `~44 + 148·n_inputs` duffs, so
 * 10 000 covers ~67 inputs — far beyond a typical contact account. If the
 * real fee ever exceeds it anyway, the build itself fails pre-broadcast
 * with the engine's typed shortfall and the ORIGINAL BIP44 error is
 * rethrown — never a broadcast of an underfunded transaction.
 */
internal const val RECEIVAL_FALLBACK_FEE_HEADROOM_DUFFS = 10_000L

/**
 * One DashPay receiving-funds account parsed from the account-balance
 * snapshot — a fallback funding candidate.
 *
 * @property derivationPath the account-level DIP-15 path EXACTLY as the
 *   enumeration reported it (`m/9'/coin'/15'/{i}'/0x<user>/0x<friend>`),
 *   handed VERBATIM to `buildSignedPaymentWithToken` as `fundingPath` —
 *   the Rust selector compares it against the very string this call
 *   produced, and a non-matching path is a hard error, never a silent
 *   BIP44 fallback.
 * @property confirmedDuffs the account's confirmed balance.
 * @property friendIdentityIdHex the contact's identity id (lower hex) —
 *   log/diagnostic only.
 */
internal data class ReceivalFundingAccount(
    val derivationPath: String,
    val confirmedDuffs: Long,
    val friendIdentityIdHex: String
)

/**
 * Parse the `accountBalances` JSON array (see
 * `DashpayNative.walletManagerAccountBalances`) into the DashPay
 * receiving-funds fallback candidates: rows with
 * `typeTag == `[ACCOUNT_TYPE_TAG_DASHPAY_RECEIVING_FUNDS] AND a non-null,
 * non-empty `derivationPath`. Rows of any other type, and receival rows
 * the engine reports no account-level path for (JSON `null`), are never
 * candidates. Malformed input returns empty (the caller rethrows the
 * original shortfall — fail closed).
 */
internal fun parseReceivalFundingAccounts(accountBalancesJson: String?): List<ReceivalFundingAccount> {
    if (accountBalancesJson.isNullOrEmpty()) return emptyList()
    return try {
        val rows = org.json.JSONArray(accountBalancesJson)
        val accounts = ArrayList<ReceivalFundingAccount>(rows.length())
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            if (row.optInt("typeTag", -1) != ACCOUNT_TYPE_TAG_DASHPAY_RECEIVING_FUNDS) continue
            // isNull covers both an absent key and an explicit JSON null
            // (optString would coerce the NULL sentinel to the STRING
            // "null" on Android's org.json — never path-safe).
            if (row.isNull("derivationPath")) continue
            val path = row.getString("derivationPath")
            if (path.isEmpty()) continue
            accounts.add(
                ReceivalFundingAccount(
                    derivationPath = path,
                    confirmedDuffs = row.optLong("confirmed", 0L),
                    friendIdentityIdHex = row.optString("friendIdentityId", "")
                )
            )
        }
        accounts
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * The SINGLE receival account to fund the fallback send from: the one
 * with the LARGEST `confirmed` among those covering
 * `amount + feeHeadroom` on their own — or null when none does.
 * Accounts are NEVER unioned: one funding account per send
 * (dashpay/platform#4184 funding-domain isolation — the Rust selector
 * enforces the same rule; this pick just decides WHICH single account to
 * name). `confirmed` only — unconfirmed receival funds never qualify.
 */
internal fun pickReceivalFundingAccount(
    candidates: List<ReceivalFundingAccount>,
    amountDuffs: Long,
    feeHeadroomDuffs: Long = RECEIVAL_FALLBACK_FEE_HEADROOM_DUFFS
): ReceivalFundingAccount? = candidates
    .filter { it.confirmedDuffs >= amountDuffs + feeHeadroomDuffs }
    .maxByOrNull { it.confirmedDuffs }

// ── Multi-account consolidation (receival → own BIP44) — pure pieces ──

/**
 * `AccountTypeTagFFI::Standard` / `StandardAccountTypeTagFFI::Bip44` — the
 * `typeTag` / `standardTag` pair the account-balance JSON reports for the
 * unmixed BIP44 funds account (values from rs-platform-wallet-ffi
 * `wallet_restore_types.rs`: `Standard = 0`, `Bip44 = 0`).
 */
internal const val ACCOUNT_TYPE_TAG_STANDARD = 0
internal const val STANDARD_ACCOUNT_TAG_BIP44 = 0

/**
 * The BIP44 account 0 `confirmed` balance from the same account-balance
 * JSON snapshot [parseReceivalFundingAccounts] reads — the consolidation
 * stop-condition's baseline ("existing BIP44 balance"). Missing row or
 * malformed input returns 0: a conservative baseline only ever sweeps MORE
 * accounts than strictly needed, and every sweep is a self-send.
 */
internal fun parseBip44ConfirmedDuffs(accountBalancesJson: String?): Long {
    if (accountBalancesJson.isNullOrEmpty()) return 0L
    return try {
        val rows = org.json.JSONArray(accountBalancesJson)
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            if (row.optInt("typeTag", -1) != ACCOUNT_TYPE_TAG_STANDARD) continue
            if (row.optInt("standardTag", -1) != STANDARD_ACCOUNT_TAG_BIP44) continue
            if (row.optInt("index", -1) != 0) continue
            return row.optLong("confirmed", 0L)
        }
        0L
    } catch (e: Exception) {
        0L
    }
}

/**
 * The BIP44 account 0 SPENDABLE balance (`confirmed + unconfirmed` — the
 * same `WalletCoreBalance.spendable()` split the native wallet-wide
 * `balance()` read serves) from the account-balance JSON snapshot — or
 * null when the row is missing or the input is malformed, so the caller
 * can fall back to a coarser figure instead of showing 0.
 */
internal fun parseBip44SpendableDuffs(accountBalancesJson: String?): Long? {
    if (accountBalancesJson.isNullOrEmpty()) return null
    return try {
        val rows = org.json.JSONArray(accountBalancesJson)
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            if (row.optInt("typeTag", -1) != ACCOUNT_TYPE_TAG_STANDARD) continue
            if (row.optInt("standardTag", -1) != STANDARD_ACCOUNT_TAG_BIP44) continue
            if (row.optInt("index", -1) != 0) continue
            return row.optLong("confirmed", 0L) + row.optLong("unconfirmed", 0L)
        }
        null
    } catch (e: Exception) {
        null
    }
}

/**
 * The POST-CUTOVER "max sendable" figure in duffs — what a MAX (send-all)
 * can actually DELIVER TO BIP44 before the final drain's own fee:
 *
 *   BIP44 account 0 spendable (confirmed + unconfirmed)
 *   + Σ over SWEEPABLE receival accounts of (confirmed − per-sweep fee headroom)
 *
 * Receival terms count the SAME rows [parseReceivalFundingAccounts] reads,
 * restricted to sweepable ones (`confirmed > `
 * [RECEIVAL_FALLBACK_FEE_HEADROOM_DUFFS] — the JNI boundary requires a
 * positive sweep output) and net of the per-account sweep-fee headroom, so
 * the quote never overstates what the sweep-then-drain max send delivers:
 * the real per-sweep fee is far below the headroom and its change routes
 * back to BIP44, so actual delivery only ever EXCEEDS this figure (minus
 * the final drain fee, which the drain absorbs exactly like today's
 * quote). Receival `confirmed` only — unconfirmed receival funds are
 * never swept. Null when the snapshot is missing/malformed or has no
 * BIP44 row (caller falls back to the wallet-wide total).
 */
internal fun maxSendableDuffs(
    accountBalancesJson: String?,
    sweepFeeHeadroomDuffs: Long = RECEIVAL_FALLBACK_FEE_HEADROOM_DUFFS
): Long? {
    val bip44SpendableDuffs = parseBip44SpendableDuffs(accountBalancesJson) ?: return null
    val receivalNetDuffs = parseReceivalFundingAccounts(accountBalancesJson)
        .filter { it.confirmedDuffs > sweepFeeHeadroomDuffs }
        .sumOf { it.confirmedDuffs - sweepFeeHeadroomDuffs }
    return bip44SpendableDuffs + receivalNetDuffs
}

/**
 * The receival accounts a consolidation may SWEEP (one self-sweep tx per
 * account, largest confirmed first) when no single account covers
 * `amount + feeHeadroom` — or empty when consolidation must not run:
 *
 * - the SUM of ALL candidates' confirmed balances is below
 *   `amount + feeHeadroom` (consolidating could not make the send
 *   fundable — rethrow the shortfall instead), or
 * - no candidate is individually sweepable (a sweep's fixed output is
 *   `confirmed − feeHeadroom`, which the JNI boundary requires positive).
 *
 * The returned list is the full sweepable set in sweep order; the EXECUTOR
 * stops early once the consolidated BIP44 total covers the send (it knows
 * the actual per-sweep change figures, this planner does not). Accounts
 * are still NEVER unioned inside one transaction — consolidation is one
 * single-account self-sweep per account, serially.
 */
internal fun planConsolidationSweeps(
    candidates: List<ReceivalFundingAccount>,
    amountDuffs: Long,
    feeHeadroomDuffs: Long = RECEIVAL_FALLBACK_FEE_HEADROOM_DUFFS
): List<ReceivalFundingAccount> {
    val totalConfirmed = candidates.sumOf { it.confirmedDuffs }
    if (totalConfirmed < amountDuffs + feeHeadroomDuffs) return emptyList()
    return candidates
        .filter { it.confirmedDuffs > feeHeadroomDuffs }
        .sortedByDescending { it.confirmedDuffs }
}
// ── Deferred (BIP70/BIP270) payment ───────────────────────────────────

/**
 * A BIP70/BIP270 deferred payment: built and signed by the SDK with its
 * funding inputs RESERVED engine-side, but NOT broadcast. [rawTxBytes] is
 * what the BIP70 `Payment` message carries to the merchant; after the
 * merchant acks, [SdkL1SendService.broadcastDeferredPayment] consumes the
 * reservation, and a pre-ack failure releases it via
 * [SdkL1SendService.releaseDeferredPayment]. If neither happens (process
 * death), the engine's reservation TTL sweep reclaims the inputs.
 *
 * [native] is the SDK's owning reservation object
 * (`ManagedPlatformWallet.SignedCoreTransaction` in production, whose GC
 * backstop also releases an abandoned reservation) — opaque here so the
 * source seam stays host-JVM testable.
 */
class SdkDeferredPayment internal constructor(
    val txidHex: String,
    val rawTxBytes: ByteArray,
    val feeDuffs: Long,
    internal val native: Any?,
    /**
     * What the single non-OP_RETURN output actually pays, in duffs.
     *
     * For an explicit-amount build this is the amount the caller asked for.
     * For a DRAIN it is the figure the ENGINE computed (total inputs − fee,
     * no change) — the caller never supplied it, so this is the only way to
     * learn what the transaction will deliver. A max swap deposit must check
     * this against the quoted amount BEFORE broadcasting: paying a vault less
     * than quoted is under-delivery, which Maya and NEAR Intents refuse.
     *
     * 0 when the source could not report it (fakes, or an SDK too old to
     * expose it); callers treat 0 as "unknown" rather than "pays nothing".
     */
    val deliverableDuffs: Long = 0
)

// ── Source seam ───────────────────────────────────────────────────────

/**
 * Seam over the Kotlin SDK's Core send surface
 * (`ManagedPlatformWallet.sendToAddresses`), so the flag/gate/
 * no-double-broadcast orchestration in [SdkL1SendService] is host-JVM
 * unit-testable — the real call needs `libdash_sdk`.
 */
interface SdkL1SendSource {
    /** Same contract as [SdkDashPayWriteSource.boundWalletIdOrNull]. */
    suspend fun boundWalletIdOrNull(): String?

    /**
     * Build, sign and broadcast a single-recipient Core payment of
     * [amountDuffs] to [addressBase58] from the SDK wallet's BIP44
     * account 0 (the same seed/coins dashj spends — parity-proven by the
     * shadow harness). Returns the broadcast txid as lowercase hex;
     * throws on any failure ([classifyCoreSendFailure] decides what the
     * throw proves).
     */
    suspend fun sendToAddress(walletIdHex: String, addressBase58: String, amountDuffs: Long): String

    /**
     * SWEEP-ALL mode of the receival-account consolidation, for the MAX
     * (send-all) path: sweep EVERY DashPay receival account holding
     * sweepable confirmed funds (`confirmed > `
     * [RECEIVAL_FALLBACK_FEE_HEADROOM_DUFFS]) into this wallet's own BIP44
     * account — one self-sweep tx per account via the SAME primitive the
     * shortfall consolidation uses (accounts are NEVER unioned in a tx;
     * each sweep goes to its OWN fresh BIP44 address) — then AWAIT the
     * swept outputs becoming BIP44-spendable (IS-locks land in ~2s), so
     * the caller's subsequent drain can deliver them. Unlike the
     * shortfall consolidation there is NO stop condition: a MAX send must
     * deliver everything, so every sweepable account is swept.
     *
     * Returns the total duffs credited toward BIP44 (sweep outputs +
     * change) — 0 when no receival account holds sweepable funds (the
     * common case: pure no-op, one balance read). THROWS on any
     * enumeration/sweep/await failure; every failure is funds-safe — the
     * un-swept funds stay in their receival accounts, and any sweep
     * already broadcast is a SELF-SEND consolidating into BIP44 (the next
     * attempt simply finds the funds there). Nothing of the caller's
     * PAYMENT has been built or broadcast, so the caller may classify the
     * throw NotBroadcast by construction. Default: nothing to sweep
     * (sources without receival-account support).
     */
    suspend fun sweepReceivalAccountsForSendAll(walletIdHex: String): Long = 0L

    /**
     * The SDK wallet's spendable balance in duffs — `confirmed +
     * unconfirmed` from the lock-free native snapshot (immature excluded,
     * like dashj's ESTIMATED). NOTE: the Rust side's "locked" balance
     * bucket is the ENGINE's own state (its reservation/lock bookkeeping;
     * key-wallet `Utxo.is_locked` is false on every creation path) — it is
     * NOT dashj's app-side `Wallet.lockOutput` set (the CrowdNode account
     * locks). App-locked outputs are therefore INCLUDED in this figure and
     * selectable by the SDK's coin selection; that mismatch is exactly why
     * [SdkL1SendService] refuses the drain while any app-locked spendable
     * output exists. Feeds [sendAllFloorDuffs]. Default throws: only the
     * production source (and fakes that exercise send-all) need it.
     */
    suspend fun spendableBalanceDuffs(walletIdHex: String): Long =
        throw UnsupportedOperationException("send-all not supported by this source")

    /**
     * Run [block] while holding this source's app-owned core-send lock.
     * [SdkL1SendService] wraps the send-all attempt AND its single
     * adjust-down retry in ONE acquisition, so a concurrent plain send
     * cannot interleave between the two attempts and change the drained
     * balance. This is an app-side [kotlinx.coroutines.sync.Mutex], NOT a
     * mutex owned by the SDK binary (the pinned v41int2 AAR exposes no such
     * accessor); the cross-path double-select backstop is the Rust engine's
     * atomic UTXO reservation. The mutex is NOT reentrant: [block] must not
     * nest another [withCoreSendLock] — the only permitted call inside is
     * [sendAllToAddress], which never touches this lock. Default: runs
     * [block] directly (sources that don't serialize a drain).
     */
    suspend fun <T> withCoreSendLock(walletIdHex: String, block: suspend () -> T): T = block()

    /**
     * Build, sign and broadcast a SEND-ALL (drain) of BIP44 account 0 to
     * [addressBase58]: every spendable input, one output worth
     * `total − fee` (engine-computed), no change —
     * `SelectionStrategy::All` via the bound
     * `coreTxBuilderSetSelectionStrategy` knob ([CoreSendAllNative]).
     * [floorDuffs] is the deliver-at-least floor; an engine-reported
     * shortfall against it throws the pre-broadcast "Insufficient funds"
     * build failure ([isSendAllShortfall]). Returns the broadcast txid as
     * lowercase hex; throws classify via [classifyCoreSendFailure] exactly
     * like [sendToAddress].
     *
     * CONCURRENCY CONTRACT: the caller MUST hold the wallet's core-send
     * lock via [withCoreSendLock] across this call (and any retry) — this
     * method itself is deliberately lock-free because the mutex is not
     * reentrant. Default throws: see [spendableBalanceDuffs].
     */
    suspend fun sendAllToAddress(walletIdHex: String, addressBase58: String, floorDuffs: Long): String =
        throw UnsupportedOperationException("send-all not supported by this source")

    /**
     * Build and sign a multi-recipient Core payment from BIP44 account 0
     * with its funding inputs RESERVED, WITHOUT broadcasting — the BIP70/
     * BIP270 deferred-submission primitive
     * (`ManagedPlatformWallet.buildSignedPayment`). Selection, reservation
     * and signing commit as one atomic native operation, so a concurrent
     * send cannot double-select the same inputs. Exactly one of
     * [broadcastDeferredPayment] / [releaseDeferredPayment] should follow;
     * an orphaned reservation falls to the engine's TTL sweep (and the
     * production object's GC backstop). Default throws: only the
     * production source (and fakes exercising BIP70) need it.
     */
    suspend fun buildDeferredPayment(
        walletIdHex: String,
        recipients: List<Pair<String, Long>>
    ): SdkDeferredPayment =
        throw UnsupportedOperationException("deferred (BIP70) payment not supported by this source")

    /**
     * [buildDeferredPayment] in the MAYACHAIN deposit shape
     * (`docs.mayaprotocol.com` → "Sending Transactions", UTXO chains):
     * one recipient output to the Asgard vault at VOUT0, the swap [memo]
     * as a zero-value OP_RETURN at VOUT1, change routed BACK TO THE FIRST
     * INPUT'S ADDRESS at VOUT2 (MAYAChain identifies the depositor by
     * VIN0 and pays refunds there), no BIP-69 reordering. Same
     * reservation contract as [buildDeferredPayment]: exactly one of
     * [broadcastDeferredPayment] / [releaseDeferredPayment] should
     * follow. Default throws: only the production source (and fakes
     * exercising Maya) need it.
     */
    suspend fun buildDeferredMayaDeposit(
        walletIdHex: String,
        vaultAddressBase58: String,
        vaultDuffs: Long,
        memo: ByteArray,
        drain: Boolean = false
    ): SdkDeferredPayment =
        throw UnsupportedOperationException("Maya deposit build not supported by this source")

    /**
     * Broadcast a payment built by [buildDeferredPayment], consuming its
     * reservation, and return the broadcast txid as lowercase hex. Throws
     * on failure ([classifyDeferredBroadcastFailure] decides what the
     * throw proves — the reservation-token errors are definitively
     * pre-network). Default throws: see [buildDeferredPayment].
     */
    suspend fun broadcastDeferredPayment(walletIdHex: String, payment: SdkDeferredPayment): String =
        throw UnsupportedOperationException("deferred (BIP70) payment not supported by this source")

    /**
     * Release the reservation of a payment built by [buildDeferredPayment]
     * WITHOUT broadcasting — the abandoned/nacked arm. Idempotent
     * engine-side: releasing a consumed or already-released token is a
     * harmless no-op. Default throws: see [buildDeferredPayment].
     */
    suspend fun releaseDeferredPayment(walletIdHex: String, payment: SdkDeferredPayment): Unit =
        throw UnsupportedOperationException("deferred (BIP70) payment not supported by this source")

    /**
     * The LOWEST-index unused EXTERNAL (receive-chain) address of the
     * wallet's BIP44 account 0, read from the SDK's Room mirror of the
     * ENGINE-maintained address pool (`core_addresses`, fed by
     * `onPersistAccountAddressPoolEntry`) — the SDK's canonical
     * current-address pattern (KotlinExampleApp `ReceiveAddressSheet`,
     * iOS `nextCoreReceiveAddress`), used here as the BIP70
     * `Payment.refund_to` source with no dashj keychain involved. Null
     * when the account or pool rows are absent (fresh install
     * mid-first-sync, wiped DB).
     *
     * UPSTREAM GAP (dashpay/platform — Kotlin parity, small): the engine
     * ALREADY exposes this as `core_wallet_next_receive_address` in
     * rs-platform-wallet-ffi (iOS binds it directly:
     * `SwiftDashSDKReceiveAddressReader` → `coreWallet().nextReceiveAddress`),
     * but no rs-unified-sdk-jni/Kotlin plumbing exists. Once ported, swap
     * this Room read for the FFI call — same answer (lowest unused by the
     * engine's used-set, same cold-start caveat), engine-authoritative.
     * Neither carries an issued-marker, so per-invoice
     * (dashj-`freshReceiveAddress`-style) handout remains a separate,
     * later ask. Default throws: only the production source (and BIP70
     * fakes) need it.
     */
    suspend fun unusedExternalAddress(walletIdHex: String): String? =
        throw UnsupportedOperationException("address-pool reads not supported by this source")

    /**
     * [sendAllToAddress] aimed at the DIP-9 CoinJoin account
     * (`CoreTransactionBuilder.AccountType.COIN_JOIN`, index 0) instead of
     * BIP44 — the post-upgrade mixed-funds migration's "keep it spendable on
     * L1" option. Single-account by construction: key-wallet's `set_funding`
     * seeds inputs from that ONE account's UTXO map, so BIP44 coins are never
     * co-spent. [addressBase58] MUST be an address the same wallet owns.
     * Same concurrency contract as [sendAllToAddress]. Default throws.
     */
    suspend fun drainCoinJoinToAddress(
        walletIdHex: String,
        addressBase58: String,
        floorDuffs: Long
    ): String = throw UnsupportedOperationException("CoinJoin drain not supported by this source")
}

/** Production [SdkL1SendSource]: boots the SDK on demand. */
internal class DashSdkL1SendSource(
    private val service: DashSdkService,
    /**
     * Derives a FRESH receive address of THIS wallet's unmixed BIP44
     * account — the consolidation sweeps' destination. The pinned AAR
     * exposes no receive-address API ([ManagedCoreWallet] /
     * `ManagedPlatformWallet` have none), so the production wiring uses
     * dashj's `WalletData.freshReceiveAddressString()` — safe because both
     * stacks derive BIP44 account 0 from the same seed (the established
     * precedent: [CoinJoinFundsMigrationService.combineIntoUnmixedBalance]).
     * Called once per sweep so different contacts' sweeps never share an
     * output address. A throw is contained by the consolidation's
     * rethrow-the-original-shortfall path (default: consolidation disabled).
     */
    private val freshOwnBip44Address: () -> String = {
        throw UnsupportedOperationException("no own-address provider configured")
    }
) : SdkL1SendSource {

    /**
     * App-owned serialization lock for the send-all (drain) path. A single
     * [Mutex] is sufficient: only one wallet is ever bound to this source, so
     * there is exactly one drain sequence to serialize. It guards the whole
     * send-all attempt + its single adjust-down retry ([withCoreSendLock]) so
     * a concurrent plain send cannot interleave between the two and change the
     * drained balance. This is NOT the SDK's own internal build mutex — the
     * pinned v41int2 AAR exposes no `coreSendMutex` field/accessor. The
     * cross-path double-select backstop is the Rust engine's atomic UTXO
     * reservation (in setFunding/buildSigned), not this lock.
     */
    private val coreSendMutex = Mutex()

    private companion object {
        private val log = LoggerFactory.getLogger(DashSdkL1SendSource::class.java)

        /**
         * Total bound on waiting for consolidation-swept funds to become
         * spendable on the BIP44 path (the SDK funds from `is_confirmed ||
         * is_instantlocked`; IS-locks land in ~2s, so this covers many
         * multiples of the expected wait).
         */
        private const val CONSOLIDATION_SEND_TIMEOUT_MS = 30_000L

        /** Interval between normal-path retries of the consolidated send. */
        private const val CONSOLIDATION_SEND_RETRY_INTERVAL_MS = 2_000L

        /**
         * Decode a lowercase/uppercase hex wallet id (the manager's map key)
         * into the 32-byte form `PlatformWalletManager.accountBalances`
         * takes. Throws on malformed input — contained by the fallback's
         * balance-read catch (rethrows the original shortfall).
         */
        private fun hexToBytes(hex: String): ByteArray {
            require(hex.length % 2 == 0) { "hex string must have even length" }
            return ByteArray(hex.length / 2) { i ->
                val hi = Character.digit(hex[2 * i], 16)
                val lo = Character.digit(hex[2 * i + 1], 16)
                require(hi >= 0 && lo >= 0) { "malformed hex wallet id" }
                ((hi shl 4) + lo).toByte()
            }
        }
    }

    private suspend fun manager(): org.dashfoundation.dashsdk.wallet.PlatformWalletManager {
        service.ensureStarted()
        return checkNotNull(service.walletManagerOrNull()) {
            "SDK wallet manager missing after ensureStarted()"
        }
    }

    override suspend fun boundWalletIdOrNull(): String? =
        manager().wallets.value.keys.singleOrNull()

    override suspend fun sendToAddress(
        walletIdHex: String,
        addressBase58: String,
        amountDuffs: Long
    ): String {
        val manager = manager()
        val wallet = checkNotNull(manager.wallets.value[walletIdHex]) { "SDK wallet not loaded" }
        // Example-app call shape (SendTransactionScreen's CORE_TO_CORE flow):
        // builder defaults for fee rate / selection strategy / change handling
        // (setFunding sets inputs AND the change address Rust-side), signed via
        // the manager's mnemonic resolver — no private key crosses the
        // boundary. BIP44 account 0 is sendToAddresses' default.
        return try {
            wallet.sendToAddresses(
                recipients = listOf(addressBase58 to amountDuffs),
                network = toSdkNetwork(Constants.NETWORK_PARAMETERS),
                coreSignerHandle = manager.mnemonicResolverHandle
            )
        } catch (shortfall: DashSdkError.PlatformWallet.CoreInsufficientFunds) {
            // BIP44 account 0 cannot cover the payment (typed pre-broadcast
            // selector shortfall — nothing reached the wire). Try the ONE
            // DashPay receival account that can, or rethrow unchanged.
            sendFromReceivalAccountOrRethrow(manager, wallet, walletIdHex, addressBase58, amountDuffs, shortfall)
        }
    }

    /**
     * Fallback for a BIP44 shortfall: spend from a SINGLE DashPay
     * receiving-funds account (a contact's payments to us) via the
     * deferred build-token flow —
     * `buildSignedPaymentWithToken(fundingPath = <the account's own
     * derivationPath>)` → `broadcastSigned`.
     *
     * Funds-safety contract:
     * - The funding path is the `derivationPath` string the
     *   account-balance enumeration itself reported, passed VERBATIM —
     *   never hand-built. Accounts are NEVER unioned; if no single
     *   receival account covers `amount + `
     *   [RECEIVAL_FALLBACK_FEE_HEADROOM_DUFFS], the ORIGINAL
     *   [DashSdkError.PlatformWallet.CoreInsufficientFunds] is rethrown
     *   unchanged (classified NotBroadcast → today's dashj
     *   InsufficientMoneyException surface).
     * - Any balance-read/parse/build failure also rethrows the ORIGINAL
     *   shortfall (with the new failure attached as suppressed): all of
     *   those steps are strictly pre-broadcast, so NotBroadcast stays
     *   provable.
     * - A failure AFTER the build releases the reservation on every path:
     *   [ManagedPlatformWallet.SignedCoreTransaction] is the reservation
     *   owner and `use { }` closes it on any exit — a real release when
     *   the token was never consumed, a native no-op after a consuming
     *   `broadcastSigned` attempt (whose ambiguous-outcome policy keeps
     *   the inputs reserved Rust-side, exactly like the normal send). A
     *   broadcast failure rethrows the BROADCAST throwable — NOT the
     *   original shortfall — so [classifyCoreSendFailure] can still
     *   distinguish a definitive rejection from an ambiguous outcome
     *   (which must never fall back to dashj: potential double PAY).
     * - `feePerKb = 0` = the SDK default rate, the same
     *   `FeeRate::normal()` the primary path's builder uses; change from
     *   the build routes to the unmixed BIP44 account (structural,
     *   engine-side).
     */
    private suspend fun sendFromReceivalAccountOrRethrow(
        manager: org.dashfoundation.dashsdk.wallet.PlatformWalletManager,
        wallet: org.dashfoundation.dashsdk.wallet.ManagedPlatformWallet,
        walletIdHex: String,
        addressBase58: String,
        amountDuffs: Long,
        shortfall: DashSdkError.PlatformWallet.CoreInsufficientFunds
    ): String {
        val accountBalancesJson = try {
            manager.accountBalances(hexToBytes(walletIdHex))
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("SDK l1Send fallback: account-balance read failed; rethrowing the BIP44 shortfall", t)
            shortfall.addSuppressed(t)
            throw shortfall
        }
        val candidates = parseReceivalFundingAccounts(accountBalancesJson)
        val account = pickReceivalFundingAccount(candidates, amountDuffs)
        if (account == null) {
            if (candidates.isEmpty()) throw shortfall
            // No SINGLE receival account suffices. If the SUM across accounts
            // does, consolidate them into BIP44 (one self-sweep per account —
            // accounts still never share a transaction) and retry the send.
            return consolidateReceivalAccountsAndResendOrRethrow(
                manager, wallet, addressBase58, amountDuffs, candidates,
                parseBip44ConfirmedDuffs(accountBalancesJson), shortfall
            )
        }
        log.info(
            "SDK l1Send: BIP44 account 0 insufficient; spending from DashPay receival account " +
                "(contact={}…, confirmed={} duffs) via its reported fundingPath",
            account.friendIdentityIdHex.take(16),
            account.confirmedDuffs
        )
        val payment = try {
            wallet.buildSignedPaymentWithToken(
                recipients = listOf(addressBase58 to amountDuffs),
                coreSignerHandle = manager.mnemonicResolverHandle,
                feePerKb = 0,
                fundingPath = account.derivationPath
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // Build failures are pre-broadcast by construction (select +
            // reserve + sign + register; no network) and mint no token, so
            // there is nothing to release. Rethrow the ORIGINAL shortfall —
            // provably NotBroadcast — with this failure as diagnostics.
            log.warn("SDK l1Send fallback: receival-account build failed pre-broadcast; rethrowing the BIP44 shortfall", t)
            shortfall.addSuppressed(t)
            throw shortfall
        }
        // use{} releases the reservation on EVERY failure exit (close() is a
        // synchronous, idempotent native release — cancellation-safe, and a
        // no-op once broadcastSigned consumed the token); the object overload
        // of broadcastSigned keeps the token GC-reachable across the call.
        val txidHex = payment.use { wallet.broadcastSigned(payment) }
        log.info(
            "SDK l1Send fallback: broadcast {} duffs from the receival account (contact={}…), txid {}",
            amountDuffs, account.friendIdentityIdHex.take(16), txidHex
        )
        return txidHex
    }

    /**
     * One broadcast receival-account self-sweep: the txid and the duffs
     * credited toward BIP44 (sweep output + change — change routes to the
     * unmixed BIP44 account structurally, so both count).
     */
    private data class ReceivalSweepResult(val txidHex: String, val creditedDuffs: Long)

    /**
     * Stage-tagged failure of one receival self-sweep — [note] is the
     * abort note the callers attach ("own-address derivation failed",
     * "a sweep build failed pre-broadcast", "a sweep broadcast failed").
     * Every stage is funds-safe: nothing of the caller's PAYMENT was
     * built, and a broadcast-stage failure concerns a SELF-SEND only.
     */
    private class ReceivalSweepException(val note: String, cause: Throwable) : Exception(note, cause)

    /**
     * The SHARED per-account sweep primitive (one self-sweep tx per
     * receival account — accounts are NEVER combined in a transaction; the
     * Rust selector enforces it, this method never asks): build and
     * broadcast a fixed-amount payment of `confirmed − `
     * [RECEIVAL_FALLBACK_FEE_HEADROOM_DUFFS] duffs from [account]'s own
     * reported `fundingPath` to a FRESH own BIP44 address, via
     * `buildSignedPaymentWithToken` → `broadcastSigned` (reservation
     * released by `use { }` on every failure exit). Used by BOTH the
     * shortfall consolidation ([consolidateReceivalAccountsAndResendOrRethrow])
     * and the send-all sweep-all pass ([sweepReceivalAccountsForSendAll]).
     * Throws [ReceivalSweepException]; [CancellationException] passes
     * through unwrapped.
     */
    private suspend fun sweepReceivalAccountToOwnBip44(
        manager: org.dashfoundation.dashsdk.wallet.PlatformWalletManager,
        wallet: org.dashfoundation.dashsdk.wallet.ManagedPlatformWallet,
        account: ReceivalFundingAccount
    ): ReceivalSweepResult {
        val sweepAmountDuffs = account.confirmedDuffs - RECEIVAL_FALLBACK_FEE_HEADROOM_DUFFS
        // A FRESH own address PER SWEEP: different contacts' sweeps must
        // not share an output address (reuse would link their funds at
        // that address even across separate transactions).
        val destination = try {
            freshOwnBip44Address()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("SDK l1Send receival sweep: own-address derivation failed", t)
            throw ReceivalSweepException("own-address derivation failed", t)
        }
        val payment = try {
            wallet.buildSignedPaymentWithToken(
                recipients = listOf(destination to sweepAmountDuffs),
                coreSignerHandle = manager.mnemonicResolverHandle,
                feePerKb = 0,
                fundingPath = account.derivationPath
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // Pre-broadcast by construction (select + reserve + sign +
            // register, no network) and no token minted — nothing to
            // release for THIS sweep; earlier sweeps are self-sends.
            log.warn(
                "SDK l1Send receival sweep: build failed pre-broadcast (contact={}…)",
                account.friendIdentityIdHex.take(16),
                t
            )
            throw ReceivalSweepException("a sweep build failed pre-broadcast", t)
        }
        // use{} releases the reservation on EVERY failure exit, exactly
        // like the single-account fallback (close() is a synchronous,
        // idempotent native release — a no-op once broadcastSigned
        // consumed the token).
        val sweepTxid = try {
            payment.use { wallet.broadcastSigned(payment) }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // Even an AMBIGUOUS sweep-broadcast outcome is funds-safe: the
            // sweep pays this wallet's OWN BIP44 address, so whether or
            // not it reached the network the funds remain the user's.
            log.warn(
                "SDK l1Send receival sweep: broadcast failed (contact={}…) — a SELF-SEND, funds safe either way",
                account.friendIdentityIdHex.take(16),
                t
            )
            throw ReceivalSweepException("a sweep broadcast failed", t)
        }
        log.info(
            "SDK l1Send receival sweep: swept {} duffs (change {} duffs, fee {} duffs) from " +
                "receival account (contact={}…) to a fresh own BIP44 address, txid {}",
            sweepAmountDuffs,
            payment.changeDuffs,
            payment.feeDuffs,
            account.friendIdentityIdHex.take(16),
            sweepTxid
        )
        return ReceivalSweepResult(sweepTxid, sweepAmountDuffs + payment.changeDuffs)
    }

    /**
     * Multi-contact consolidation for a BIP44 shortfall that NO single
     * receival account covers but the SUM across receival accounts can
     * ([planConsolidationSweeps] non-empty):
     *
     * 1. SWEEP — for each planned account (largest confirmed first), build
     *    and broadcast a SEPARATE single-account self-sweep of
     *    `confirmed − `[RECEIVAL_FALLBACK_FEE_HEADROOM_DUFFS] duffs to a
     *    FRESH own BIP44 address, via the SAME primitive the single-account
     *    fallback uses (`buildSignedPaymentWithToken(fundingPath = <that
     *    account's reported path>)` → `broadcastSigned`, reservation
     *    released by `use { }` on every failure exit). ONE tx per account —
     *    two accounts' inputs are NEVER combined in a transaction (the Rust
     *    selector enforces it; this loop never asks). Fixed-amount sweeps,
     *    not a drain: the pinned AAR has no send-all variant that takes a
     *    `fundingPath` ([CoreSendAllNative] only drains the BIP44/CoinJoin
     *    account TYPES), so up to ~headroom duffs of dust-level residue may
     *    stay behind per account (unselected inputs); the selected inputs'
     *    change (`headroom − fee`) routes to BIP44 structurally, so it is
     *    NOT residue and counts toward the consolidated total.
     * 2. STOP sweeping once `existing BIP44 confirmed + swept output +
     *    change` covers `amount + headroom` — never more accounts than
     *    needed.
     * 3. RETRY the ORIGINAL send on the NORMAL BIP44 path
     *    (`sendToAddresses` — no recursion into this fallback) every
     *    [CONSOLIDATION_SEND_RETRY_INTERVAL_MS], up to
     *    [CONSOLIDATION_SEND_TIMEOUT_MS] total: the SDK funds from
     *    `is_confirmed || is_instantlocked` outputs and IS-locks land in
     *    ~2s, so a successful retry PROVES spendability — simpler and
     *    self-proving versus observing IS-lock events.
     *
     * Failure semantics — every abort is funds-safe:
     * - A sweep build/broadcast failure, an address-derivation failure, or
     *   the retry timeout rethrows the ORIGINAL
     *   [DashSdkError.PlatformWallet.CoreInsufficientFunds] (familiar UI
     *   error; NotBroadcast classification stays provable for the PAYMENT,
     *   which was never built) with the trigger and a partial-consolidation
     *   note attached as suppressed. Sweeps already broadcast are
     *   SELF-SENDS to this wallet's own BIP44 addresses — even an ambiguous
     *   sweep outcome moves funds only into the user's main account, so the
     *   next send attempt simply finds them there.
     * - A NON-shortfall failure of the retried send propagates AS-IS so
     *   [classifyCoreSendFailure] still sees it (an ambiguous broadcast
     *   must surface as Ambiguous — never a dashj retry).
     */
    private suspend fun consolidateReceivalAccountsAndResendOrRethrow(
        manager: org.dashfoundation.dashsdk.wallet.PlatformWalletManager,
        wallet: org.dashfoundation.dashsdk.wallet.ManagedPlatformWallet,
        addressBase58: String,
        amountDuffs: Long,
        candidates: List<ReceivalFundingAccount>,
        bip44ConfirmedDuffs: Long,
        shortfall: DashSdkError.PlatformWallet.CoreInsufficientFunds
    ): String {
        val targetDuffs = amountDuffs + RECEIVAL_FALLBACK_FEE_HEADROOM_DUFFS
        val plan = planConsolidationSweeps(candidates, amountDuffs)
        if (plan.isEmpty()) {
            log.info(
                "SDK l1Send fallback: receival funds split across {} contact account(s) " +
                    "({} duffs confirmed total, largest {}); neither one account nor the sum " +
                    "covers {} + {} duffs headroom with sweepable accounts, and accounts are " +
                    "never unioned in one tx — rethrowing the BIP44 shortfall",
                candidates.size,
                candidates.sumOf { it.confirmedDuffs },
                candidates.maxOf { it.confirmedDuffs },
                amountDuffs,
                RECEIVAL_FALLBACK_FEE_HEADROOM_DUFFS
            )
            throw shortfall
        }
        log.info(
            "SDK l1Send consolidation: no single receival account covers {} + {} duffs headroom; " +
                "consolidating up to {} receival account(s) ({} duffs confirmed) into BIP44 " +
                "(existing confirmed {} duffs) to cover the send",
            amountDuffs,
            RECEIVAL_FALLBACK_FEE_HEADROOM_DUFFS,
            plan.size,
            plan.sumOf { it.confirmedDuffs },
            bip44ConfirmedDuffs
        )
        var consolidatedDuffs = 0L
        val sweptTxids = ArrayList<String>(plan.size)
        for (account in plan) {
            if (bip44ConfirmedDuffs + consolidatedDuffs >= targetDuffs) break
            // The ORIGINAL payment was never built or broadcast, so on a
            // sweep failure rethrowing the original shortfall keeps its
            // NotBroadcast classification provable.
            val sweep = try {
                sweepReceivalAccountToOwnBip44(manager, wallet, account)
            } catch (e: ReceivalSweepException) {
                rethrowShortfallAfterConsolidation(
                    shortfall, e.cause ?: e, sweptTxids, consolidatedDuffs, e.note
                )
            }
            consolidatedDuffs += sweep.creditedDuffs
            sweptTxids.add(sweep.txidHex)
        }
        log.info(
            "SDK l1Send consolidation: consolidated {} receival account(s) ({} duffs incl. change) " +
                "toward the send of {} duffs; retrying the send for up to {} ms",
            sweptTxids.size, consolidatedDuffs, amountDuffs, CONSOLIDATION_SEND_TIMEOUT_MS
        )
        // Retry-with-backoff on the NORMAL path — success proves the swept
        // outputs became fundable. Deliberately no withTimeout: an in-flight
        // send is never cancelled mid-broadcast; the deadline is only
        // checked between attempts.
        val deadlineNanos = System.nanoTime() + CONSOLIDATION_SEND_TIMEOUT_MS * 1_000_000L
        while (true) {
            val txidHex = try {
                wallet.sendToAddresses(
                    recipients = listOf(addressBase58 to amountDuffs),
                    network = toSdkNetwork(Constants.NETWORK_PARAMETERS),
                    coreSignerHandle = manager.mnemonicResolverHandle
                )
            } catch (t: DashSdkError.PlatformWallet.CoreInsufficientFunds) {
                if (System.nanoTime() >= deadlineNanos) {
                    log.warn(
                        "SDK l1Send consolidation: swept funds not yet spendable after {} ms; " +
                            "rethrowing the BIP44 shortfall — the {} duffs swept (txids {}) are " +
                            "self-sends consolidating into BIP44, so the next attempt will succeed",
                        CONSOLIDATION_SEND_TIMEOUT_MS, consolidatedDuffs, sweptTxids
                    )
                    rethrowShortfallAfterConsolidation(
                        shortfall, t, sweptTxids, consolidatedDuffs,
                        "the swept funds were not spendable within ${CONSOLIDATION_SEND_TIMEOUT_MS} ms"
                    )
                }
                delay(CONSOLIDATION_SEND_RETRY_INTERVAL_MS)
                continue
            }
            // Any NON-shortfall throwable above propagates AS-IS so the
            // caller's classifyCoreSendFailure treats it exactly like a
            // normal-path failure (ambiguous stays ambiguous — no dashj).
            log.info(
                "SDK l1Send consolidation: original send of {} duffs succeeded after consolidating " +
                    "{} receival account(s), txid {}",
                amountDuffs, sweptTxids.size, txidHex
            )
            return txidHex
        }
    }

    /**
     * Abort a consolidation by rethrowing the ORIGINAL BIP44 [shortfall]
     * (so the UI shows the familiar insufficient-funds error and the
     * NotBroadcast classification of the never-built payment stays
     * provable), attaching [cause] and — when sweeps already broadcast — a
     * note recording that those funds are now consolidating into BIP44
     * (self-sends: safe, and available to the next attempt).
     */
    private fun rethrowShortfallAfterConsolidation(
        shortfall: DashSdkError.PlatformWallet.CoreInsufficientFunds,
        cause: Throwable,
        sweptTxids: List<String>,
        consolidatedDuffs: Long,
        note: String
    ): Nothing {
        shortfall.addSuppressed(cause)
        if (sweptTxids.isNotEmpty()) {
            shortfall.addSuppressed(
                IllegalStateException(
                    "receival-account consolidation aborted ($note) after sweeping " +
                        "$consolidatedDuffs duffs to own BIP44 addresses in ${sweptTxids.size} " +
                        "self-send tx(s) $sweptTxids — funds safe; the next send attempt can " +
                        "spend them via BIP44"
                )
            )
        }
        throw shortfall
    }

    /**
     * [SdkL1SendSource.sweepReceivalAccountsForSendAll] — the MAX (send-all)
     * completeness pass. The BIP44 drain ([CoreSendAllNative]) only ever
     * selects BIP44-account UTXOs and CANNOT fail on invisible receival
     * funds — it self-limits and succeeds — so unlike a plain send there
     * is no shortfall to trigger the receival fallback: the sweeps must
     * run UP FRONT (the on-device max-send bug: main account drained, the
     * contact-received funds silently left behind).
     *
     * The await is BALANCE-based for the same reason: a drain run before
     * the swept outputs are spendable would quietly deliver only the old
     * BIP44 funds, not fail — so retry-the-send is no proof here. BIP44
     * `confirmed` counts in-a-block OR IS-locked, exactly the funding
     * predicate (`is_confirmed || is_instantlocked`), so BIP44-confirmed
     * reaching `before + credited` PROVES the drain can select every
     * swept output (IS-locks land in ~2s).
     *
     * Failure semantics: THROWS (the enumeration read included — a blind
     * drain would repeat the silent-shortchange bug, so fail closed);
     * every abort is funds-safe (un-swept funds stay in their receival
     * accounts; broadcast sweeps are self-sends consolidating into BIP44,
     * found by the next attempt). The caller's payment was never built.
     */
    override suspend fun sweepReceivalAccountsForSendAll(walletIdHex: String): Long {
        val manager = manager()
        val wallet = checkNotNull(manager.wallets.value[walletIdHex]) { "SDK wallet not loaded" }
        val accountBalancesJson = manager.accountBalances(hexToBytes(walletIdHex))
        val sweepable = parseReceivalFundingAccounts(accountBalancesJson)
            .filter { it.confirmedDuffs > RECEIVAL_FALLBACK_FEE_HEADROOM_DUFFS }
            .sortedByDescending { it.confirmedDuffs }
        if (sweepable.isEmpty()) return 0L
        val bip44BeforeDuffs = parseBip44ConfirmedDuffs(accountBalancesJson)
        log.info(
            "SDK l1SendAll: sweeping ALL {} receival account(s) ({} duffs confirmed) into BIP44 " +
                "(confirmed {} duffs) so the drain can deliver them",
            sweepable.size,
            sweepable.sumOf { it.confirmedDuffs },
            bip44BeforeDuffs
        )
        var creditedDuffs = 0L
        val sweptTxids = ArrayList<String>(sweepable.size)
        for (account in sweepable) {
            val sweep = try {
                sweepReceivalAccountToOwnBip44(manager, wallet, account)
            } catch (e: ReceivalSweepException) {
                throw IllegalStateException(
                    "send-all receival sweep aborted (${e.note}) after sweeping $creditedDuffs " +
                        "duffs in ${sweptTxids.size} self-send tx(s) $sweptTxids — funds safe " +
                        "(un-swept funds stay in their receival accounts; swept funds are " +
                        "consolidating into BIP44); retry the send",
                    e
                )
            }
            creditedDuffs += sweep.creditedDuffs
            sweptTxids.add(sweep.txidHex)
        }
        val targetDuffs = bip44BeforeDuffs + creditedDuffs
        val deadlineNanos = System.nanoTime() + CONSOLIDATION_SEND_TIMEOUT_MS * 1_000_000L
        while (true) {
            val bip44NowDuffs = try {
                parseBip44ConfirmedDuffs(manager.accountBalances(hexToBytes(walletIdHex)))
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log.warn("SDK l1SendAll: BIP44 balance poll failed; retrying until the deadline", t)
                null
            }
            if (bip44NowDuffs != null && bip44NowDuffs >= targetDuffs) {
                log.info(
                    "SDK l1SendAll: all {} receival sweep(s) spendable on BIP44 ({} duffs " +
                        "credited incl. change); proceeding to the drain",
                    sweptTxids.size,
                    creditedDuffs
                )
                return creditedDuffs
            }
            if (System.nanoTime() >= deadlineNanos) {
                throw IllegalStateException(
                    "send-all receival sweeps not yet spendable after ${CONSOLIDATION_SEND_TIMEOUT_MS} ms " +
                        "($creditedDuffs duffs in ${sweptTxids.size} self-send tx(s) $sweptTxids — " +
                        "funds safe, consolidating into BIP44); retry the send"
                )
            }
            delay(CONSOLIDATION_SEND_RETRY_INTERVAL_MS)
        }
    }

    override suspend fun spendableBalanceDuffs(walletIdHex: String): Long {
        val manager = manager()
        val wallet = checkNotNull(manager.wallets.value[walletIdHex]) { "SDK wallet not loaded" }
        val balance = wallet.balance()
        return balance.confirmed + balance.unconfirmed
    }

    override suspend fun buildDeferredPayment(
        walletIdHex: String,
        recipients: List<Pair<String, Long>>
    ): SdkDeferredPayment {
        val manager = manager()
        val wallet = checkNotNull(manager.wallets.value[walletIdHex]) { "SDK wallet not loaded" }
        // Same call shape as sendToAddresses (builder defaults, mnemonic-
        // resolver signing) minus the broadcast: the SDK returns the signed
        // bytes with the inputs reserved behind the object's token.
        val signed = wallet.buildSignedPayment(
            recipients = recipients,
            network = toSdkNetwork(Constants.NETWORK_PARAMETERS),
            coreSignerHandle = manager.mnemonicResolverHandle
        )
        return SdkDeferredPayment(signed.txidHex, signed.rawTxBytes, signed.feeDuffs, signed)
    }

    override suspend fun buildDeferredMayaDeposit(
        walletIdHex: String,
        vaultAddressBase58: String,
        vaultDuffs: Long,
        memo: ByteArray,
        drain: Boolean
    ): SdkDeferredPayment {
        val manager = manager()
        val wallet = checkNotNull(manager.wallets.value[walletIdHex]) { "SDK wallet not loaded" }
        // Same deferred-build primitive as buildDeferredPayment, plus the
        // three MAYACHAIN builder options. The OP_RETURN is appended after
        // the vault recipient SDK-side, so preserveOutputOrder yields the
        // documented vault=VOUT0 / memo=VOUT1 shape; an over-long memo
        // throws pre-reservation.
        // A drain supplies NO amount: the engine sets the vault output to
        // (total inputs − fee), so 0 is the honest value to pass and anything
        // else would be a number the engine discards.
        val signed = wallet.buildSignedPayment(
            recipients = listOf(vaultAddressBase58 to vaultDuffs),
            network = toSdkNetwork(Constants.NETWORK_PARAMETERS),
            coreSignerHandle = manager.mnemonicResolverHandle,
            opReturnData = memo,
            preserveOutputOrder = true,
            changeToFirstInput = true,
            // A DRAIN spends every BIP44 UTXO and has the engine set the vault
            // output to (total inputs - fee), memo bytes priced in, no change:
            // `vaultDuffs` is ignored. That is what a MAX deposit means, and it
            // removes the guess the probe-measured path had to make.
            selectionStrategy = if (drain) {
                org.dashfoundation.dashsdk.wallet.CoreTransactionBuilder.SelectionStrategy.ALL
            } else {
                null
            }
        )
        return SdkDeferredPayment(
            signed.txidHex,
            signed.rawTxBytes,
            signed.feeDuffs,
            signed,
            deliverableDuffs = signed.deliverableAmountDuffs
        )
    }

    override suspend fun broadcastDeferredPayment(
        walletIdHex: String,
        payment: SdkDeferredPayment
    ): String {
        val manager = manager()
        val wallet = checkNotNull(manager.wallets.value[walletIdHex]) { "SDK wallet not loaded" }
        // The object overload keeps the reservation reachable across the
        // native call and disarms its GC backstop once consumed.
        return wallet.broadcastSigned(sdkReservationOf(payment))
    }

    override suspend fun releaseDeferredPayment(walletIdHex: String, payment: SdkDeferredPayment) {
        val manager = manager()
        val wallet = checkNotNull(manager.wallets.value[walletIdHex]) { "SDK wallet not loaded" }
        wallet.releaseReservation(sdkReservationOf(payment))
    }

    private fun sdkReservationOf(
        payment: SdkDeferredPayment
    ): org.dashfoundation.dashsdk.wallet.ManagedPlatformWallet.SignedCoreTransaction =
        checkNotNull(
            payment.native as? org.dashfoundation.dashsdk.wallet.ManagedPlatformWallet.SignedCoreTransaction
        ) { "deferred payment ${payment.txidHex} does not carry the SDK reservation object" }

    override suspend fun unusedExternalAddress(walletIdHex: String): String? {
        service.ensureStarted()
        val database = service.databaseOrNull() ?: return null
        val walletId = decodeHexOrNull(walletIdHex, walletIdHex.length / 2) ?: return null
        val account = database.accountDao()
            .getByKey(walletId, ACCOUNT_TYPE_TAG_STANDARD, 0)
            .firstOrNull { it.standardTag == STANDARD_ACCOUNT_TAG_BIP44 }
            ?: return null
        val pool = database.coreAddressDao().observeByAccount(account.id).first()
        // LOWEST-index unused entry — the SDK's canonical current-address
        // pattern (KotlinExampleApp ReceiveAddressSheet / iOS
        // nextCoreReceiveAddress: poolType external, !isUsed, balance 0,
        // min addressIndex). NOTE: dashj's Receive screen issues from the
        // same low end of this chain in Phase 1B, so refund_to will often
        // equal the currently shown receive address — accepted address
        // reuse; the pool has no issued-marker to coordinate the two.
        return pool
            .filter { it.poolTypeTag == ADDRESS_POOL_TAG_EXTERNAL && !it.isUsed && it.balance == 0L }
            .minByOrNull { it.addressIndex }
            ?.address
    }

    override suspend fun <T> withCoreSendLock(walletIdHex: String, block: suspend () -> T): T {
        // Serialize the drain under this source's app-owned [coreSendMutex].
        // Held ONCE across the whole [block] (drain attempt + adjust-down
        // retry) so a concurrent plain send cannot interleave between the
        // attempts and change the drained balance. No deadlock: nothing inside
        // [block] re-acquires it — sendAllToAddress below never touches this
        // mutex, and the plain send path (sendToAddresses) is never invoked
        // inside the block, so every acquisition is strictly sequential. The
        // cross-path double-select safety comes from the Rust engine's atomic
        // UTXO reservation, not from this lock.
        return withContext(Dispatchers.IO) {
            coreSendMutex.withLock { block() }
        }
    }

    override suspend fun sendAllToAddress(
        walletIdHex: String,
        addressBase58: String,
        floorDuffs: Long
    ): String {
        val manager = manager()
        val wallet = checkNotNull(manager.wallets.value[walletIdHex]) { "SDK wallet not loaded" }
        // Deliberately lock-free: the caller holds the wallet's
        // coreSendMutex via withCoreSendLock across this call and any retry
        // (see the interface contract) — acquiring the non-reentrant mutex
        // here again would deadlock.
        return withContext(Dispatchers.IO) {
            mapNativeErrors {
                CoreSendAllNative.buildSignBroadcastSendAll(
                    wallet,
                    toSdkNetwork(Constants.NETWORK_PARAMETERS),
                    addressBase58,
                    floorDuffs,
                    manager.mnemonicResolverHandle
                )
            }
        }
    }

    override suspend fun drainCoinJoinToAddress(
        walletIdHex: String,
        addressBase58: String,
        floorDuffs: Long
    ): String {
        val manager = manager()
        val wallet = checkNotNull(manager.wallets.value[walletIdHex]) { "SDK wallet not loaded" }
        // Lock-free for the same reason as sendAllToAddress: the caller holds
        // coreSendMutex via withCoreSendLock and it is not reentrant.
        return withContext(Dispatchers.IO) {
            mapNativeErrors {
                CoreSendAllNative.buildSignBroadcastDrainCoinJoin(
                    wallet,
                    toSdkNetwork(Constants.NETWORK_PARAMETERS),
                    addressBase58,
                    floorDuffs,
                    manager.mnemonicResolverHandle
                )
            }
        }
    }
}

// ── The send service ──────────────────────────────────────────────────

/**
 * Phase 5b of the dashj → Kotlin SDK migration
 * (`docs/kotlin-sdk-migration-plan.md`): route the app's NORMAL L1 SEND —
 * a plain Dash payment to a base58 address — through the Kotlin SDK,
 * behind [DashPayConfig.USE_KOTLIN_SDK_L1_SEND] (default OFF, NOT
 * debug-seeded — see the flag KDoc).
 *
 * Routed call site: ONLY [de.schildbach.wallet.payments.SendCoinsTaskRunner]'s
 * neutral `sendCoins(address: String, amount: Dash, …)` overload — the
 * integrations path (Coinbase / Maya). See that overload's KDoc for why
 * the main send UI stays on dashj this phase.
 *
 * ## Contract — identical to [SdkDashPayWrites] / shielded writes
 *
 * [sendToAddress] returns an [SdkWriteResult]:
 * - [SdkWriteResult.NotBroadcast] whenever the SDK path was not or could
 *   not have been used (flag off, preflight failure, evidence gate closed,
 *   or a provably pre-broadcast SDK error — [classifyCoreSendFailure]).
 *   The call site runs the existing dashj path unchanged.
 * - [SdkWriteResult.Broadcast] carrying the txid (lowercase display hex —
 *   the same string `Transaction.txId.toString()` yields, so callers'
 *   txid handling is unchanged). The call site must NOT run the dashj
 *   send.
 * - [SdkWriteResult.Ambiguous] when the failed attempt cannot be proven
 *   pre-broadcast — the call site surfaces it as an error exactly like a
 *   dashj broadcast failure and NEVER retries via dashj (see
 *   [classifyCoreSendFailure] for why a dashj retry here is a potential
 *   double PAY, not merely a double broadcast).
 *
 * ## The evidence gate (same rule as `shieldFromWallet`)
 *
 * The SDK spends from its OWN SPV view of the shared seed's UTXOs, so a
 * send is only allowed when [evaluateWalletFundingGate] passes on the
 * engine's live sync progress ([L1ShadowSyncService.progress]): engine
 * running and its filter scan caught up to the chain tip. The old
 * dashj-parity requirement is retired — the held dashj wallet is frozen
 * post-cutover and can never learn of external receives, so parity would
 * close this gate forever after the first inbound payment (and the SDK's
 * SYNCED flag is unreachable for a live shadow — see
 * [evaluateWalletFundingGate]). The helper is reused, not duplicated.
 * Spendable-balance semantics deliberately match dashj: there
 * is NO chainlocked-only cap and no app-side balance precheck — the SDK's
 * own coin selection rejects a shortfall pre-broadcast
 * (`transaction build failed` → NotBroadcast) and dashj then raises its
 * usual `InsufficientMoneyException`.
 *
 * ## Post-broadcast reconciliation with dashj (investigated, Phase 5b)
 *
 * The tx goes out over the SDK's SPV peers and spends UTXOs dashj also
 * tracks. dashj's bloom filters (loaded with its keys/outpoints) match
 * the transaction as it relays, so a CONNECTED dashj sees it in the
 * mempool within seconds, adds it as pending (Source.NETWORK) and marks
 * the coins spent; the IS-lock then locks it. If dashj is OFFLINE at that
 * moment, it only learns of the spend on its next sync (filtered blocks) —
 * in that window dashj could build a conflicting spend of the same UTXOs,
 * which the network/IS-locks would reject (funds safe, but the user sees
 * a failed send). The two stacks also briefly disagree on balance: the
 * SDK's compact-filter scan applies the spend only once MINED, while
 * dashj drops its ESTIMATED balance at mempool time — an sdk>dashj
 * INFLATED transient that the parity decider would otherwise treat as
 * corrupt shadow state after 3 consecutive probes (~3 min, comparable to
 * one block interval). [L1ShadowSyncService.noteSelfSpendBroadcast] is
 * therefore called on every successful broadcast; the decider ignores
 * inflated streaks while the marker is fresh. The deficit direction needs
 * no guard (see the decider's `recentSelfSpendMarker` doc).
 *
 * Phase 5c.2 addendum: on DEBUG builds every successful broadcast also
 * launches [SdkBridgedTransactionFactory.bridgeInBackground], which
 * commits the SDK tx straight into the dashj wallet (usually before the
 * bloom delivery above) — see that class and the [bridgeAfterBroadcast]
 * hook for the gating rationale.
 *
 * Flag off (the default): provably inert — one DataStore flag read per
 * send, [SdkL1SendSource] untouched (verified by unit test).
 */
@Singleton
class SdkL1SendService internal constructor(
    private val source: SdkL1SendSource,
    private val dashPayConfig: DashPayConfig,
    /**
     * App-side base58 address validation for the CURRENT network — a pure
     * preflight (the FFI re-validates Rust-side). Injected so the pure
     * orchestration is testable without touching [Constants] at
     * construction.
     */
    private val isValidAddress: (String) -> Boolean,
    /**
     * The SDK engine's live sync-progress snapshot — the funding-gate
     * evidence, same wiring as [ShieldedBalanceServiceImpl]. The default
     * ([ShadowSyncProgress.IDLE]) keeps the gate CLOSED (funds-safe) for
     * constructions that don't provide it.
     */
    private val l1Progress: () -> ShadowSyncProgress = { ShadowSyncProgress.IDLE },
    /**
     * Send-all guard (funds-critical): does the HELD dashj wallet still
     * track ANY app-locked output ([org.bitcoinj.wallet.Wallet.lockOutput] —
     * CrowdNode locks the outputs paying its account address) among its
     * spendable candidates? The SDK drain selects EVERY spendable UTXO
     * (`SelectionStrategy::All`) and the pinned FFI exposes NO lock or
     * exclusion API, so a drain while such an output exists would SPEND
     * app-protected funds — dashj's own `completeTx` excludes
     * `lockedOutputs` even on its emptyWallet branch, the SDK cannot.
     * Consulted only on the send-all path, never on a plain send. The
     * default is `true` (assume locked → drain blocked) so constructions
     * that don't provide the check FAIL CLOSED.
     *
     * The real fix is an upstream SDK UTXO lock/exclusion API — iOS's
     * `add_inputs_from_outpoints` binding is the porting candidate; until
     * it lands, this app-side refusal is the only fail-closed option.
     *
     * NOTE this check only covers locks the dashj wallet can SEE. The
     * drain guard additionally unions [seamOutputLockRegistry] — locks on
     * SDK-only transactions (e.g. CrowdNode API-response outputs) that the
     * held dashj wallet never learns of.
     */
    private val hasAppLockedSpendableOutputs: () -> Boolean = { true },
    /**
     * CoinJoin-drain guard ([drainCoinJoinAccountTo]), the narrow sibling of
     * [hasAppLockedSpendableOutputs]: does the held dashj wallet track any
     * app-locked output ON THE DIP-9 CoinJoin keychain? The CoinJoin drain
     * only ever selects that one account's UTXOs, so the wallet-wide check
     * would refuse the migration for every CrowdNode user over locks the
     * drain cannot reach. Default `true` → fail closed.
     */
    private val hasAppLockedCoinJoinOutputs: () -> Boolean = { true },
    /**
     * Send-all guard, seam side (B7 union): locks registered through
     * [SeamOutputLockRegistry] cover outputs of SDK-only transactions
     * (post-cutover CrowdNode API-response txs locked via
     * [de.schildbach.wallet.data.WalletDataAdapter]) that
     * [hasAppLockedSpendableOutputs]' dashj check cannot see — there is no
     * dashj `Transaction` to lock. The drain is refused when EITHER side
     * reports a lock; a registry read failure also blocks (fail closed).
     */
    private val seamOutputLockRegistry: SeamOutputLockRegistry = SeamOutputLockRegistry(),
    /** Post-broadcast hook: [L1ShadowSyncService.noteSelfSpendBroadcast]. */
    private val onSelfSpendBroadcast: () -> Unit = {},
    /**
     * Post-broadcast hook, Phase 5c.2: fire-and-forget
     * [SdkBridgedTransactionFactory.bridgeInBackground] for the broadcast
     * txid. DEBUG builds only (wired in the injected constructor):
     * bridging mutates dashj wallet state, so production stays txid-only
     * until the 5c.4 cutover. Contained — a throw here never affects the
     * already-decided [SdkWriteResult.Broadcast].
     */
    private val bridgeAfterBroadcast: (String) -> Unit = {}
) {
    @Inject
    constructor(
        sdkService: DashSdkService,
        dashPayConfig: DashPayConfig,
        l1ShadowSyncService: L1ShadowSyncService,
        bridgedTransactionFactory: SdkBridgedTransactionFactory,
        walletData: de.schildbach.wallet.data.WalletData,
        seamOutputLockRegistry: SeamOutputLockRegistry
    ) : this(
        source = DashSdkL1SendSource(
            sdkService,
            // Consolidation sweeps' destination: a FRESH own BIP44 receive
            // address. The pinned AAR exposes no receive-address API, so
            // dashj derives it — same seed, same BIP44 account 0 (the
            // CoinJoinFundsMigrationService precedent). Each call issues a
            // NEW key, so per-sweep calls never reuse an address.
            freshOwnBip44Address = { walletData.freshReceiveAddressString() }
        ),
        dashPayConfig = dashPayConfig,
        // Lazy per call: Constants untouched until a flag-gated send runs.
        isValidAddress = { address ->
            try {
                Address.fromString(Constants.NETWORK_PARAMETERS, address)
                true
            } catch (e: Exception) {
                false
            }
        },
        l1Progress = { l1ShadowSyncService.progress.value },
        hasAppLockedSpendableOutputs = {
            // The held dashj wallet stays the AUTHORITY on app-side locks
            // post-cutover (CrowdNode locks via WalletDataAdapter →
            // Wallet.lockOutput). calculateAllSpendCandidates does NOT
            // filter lockedOutputs (verified against dashj-core 22.0.4
            // bytecode; only maturity/signability), so intersecting it with
            // isLockedOutput detects exactly the outputs a drain would
            // wrongly spend. (true, true) mirrors completeTx's default
            // candidate set. A missing wallet cannot PROVE the absence of
            // locks → treated as locked (fail closed).
            @Suppress("DEPRECATION")
            val wallet = walletData.wallet
            wallet == null || wallet.calculateAllSpendCandidates(true, true).any {
                wallet.isLockedOutput(it.outPointFor)
            }
        },
        hasAppLockedCoinJoinOutputs = {
            // Narrow the same dashj-authoritative lock check to the CoinJoin
            // keychain: the drain selects ONLY that account's UTXOs, so a
            // CrowdNode lock on a BIP44 output is irrelevant to it. A missing
            // wallet, or an unreadable CoinJoin extension, cannot PROVE the
            // absence of locks → treated as locked (fail closed).
            @Suppress("DEPRECATION")
            val wallet = walletData.wallet
            wallet == null || coinJoinOutputsOrNull(wallet)?.any {
                wallet.isLockedOutput(it.outPointFor)
            } ?: true
        },
        seamOutputLockRegistry = seamOutputLockRegistry,
        onSelfSpendBroadcast = { l1ShadowSyncService.noteSelfSpendBroadcast() },
        bridgeAfterBroadcast = { txidHex ->
            // 5c.2 soak consumer: DEBUG-only until the 5c.4 cutover.
            if (BuildConfig.DEBUG) {
                bridgedTransactionFactory.bridgeInBackground(txidHex)
            }
        }
    )

    /**
     * Read-only probe of the L1 send evidence gate — THE SAME predicate
     * [sendToAddress] evaluates before broadcasting ([evaluateWalletFundingGate]
     * over the engine's live progress: engine running + filter scan caught
     * up to the chain tip). Extracted so the debug settings screen can show
     * the live gate state without approximating the rule. Never mutates
     * anything and never throws — a progress-read failure reads as a closed
     * gate, exactly as it would on a real send ([safeProgress]).
     * NOTE: a real send has additional preflights (flag, address, wallet
     * binding), so an open gate means "the SDK engine WOULD be used if
     * those pass", not a guarantee.
     */
    internal fun probeSendGate(): WalletFundingGate = evaluateWalletFundingGate(safeProgress())

    /**
     * Attempt the SDK L1 send. One broadcast attempt, classified by
     * [classifyCoreSendFailure]; every preflight failure is
     * [SdkWriteResult.NotBroadcast] by construction.
     *
     * @param emptyWallet dashj's send-all mode. PRE-CUTOVER it stays on
     *   dashj (NotBroadcast — today's behavior, byte-identical).
     *   POST-CUTOVER it routes through the SDK drain
     *   ([SdkL1SendSource.sendAllToAddress] / [CoreSendAllNative]:
     *   `SelectionStrategy::All` — all spendable inputs, one output worth
     *   `total − fee`, no change), with the iOS-validated max pattern: the
     *   first attempt floors the deliverable at
     *   [sendAllFloorDuffs]`(spendable)`; an engine-reported
     *   insufficient-at-fee ([isSendAllShortfall], provably pre-broadcast)
     *   is retried ONCE engine-authoritatively (floor 1, deliverable
     *   `total − fee`), with BOTH attempts under a single
     *   [SdkL1SendSource.withCoreSendLock] acquisition. The drain is
     *   REFUSED (NotBroadcast) while the held dashj wallet tracks any
     *   app-locked spendable output OR the seam registry holds any lock —
     *   see [hasAppLockedSpendableOutputs] and [SeamOutputLockRegistry].
     *   Before the drain, ALL DashPay receival accounts holding sweepable
     *   confirmed funds are swept into BIP44
     *   ([SdkL1SendSource.sweepReceivalAccountsForSendAll] — the BIP44
     *   drain cannot see them and self-limits without failing, so the
     *   plain-send shortfall fallback never fires on a max send); a sweep
     *   failure aborts NotBroadcast with every fund safe.
     *   [amount] is display-typed for a send-all — the
     *   engine decides the deliverable — but must still be positive.
     * @param beforeBroadcast invoked after ALL preflights pass and before
     *   ANY broadcast — for a plain send that is immediately before the
     *   single broadcast attempt; for a send-all it also precedes the
     *   receival self-sweeps, so a throw leaves nothing broadcast at all.
     *   The call site's dashj-equivalent pre-send conditions
     *   (leftover-balance check, which may throw
     *   `LeftoverBalanceException`). A throw here propagates
     *   unclassified, exactly as it would on the dashj path; nothing has
     *   been broadcast yet.
     */
    suspend fun sendToAddress(
        addressBase58: String,
        amount: Dash,
        emptyWallet: Boolean,
        beforeBroadcast: suspend () -> Unit = {}
    ): SdkWriteResult<String> {
        val operation = if (emptyWallet) "l1SendAll" else "l1Send"
        if (!isEnabled()) return SdkWriteResult.NotBroadcast("flag off")
        if (emptyWallet && !cutoverCommitted()) {
            // Pre-cutover the dashj emptyWallet path is live and stays the
            // owner of send-all (unchanged soak behavior). Post-cutover the
            // drain below takes over — dashj cannot broadcast while held.
            return notBroadcast(operation, "empty-wallet (send-all) stays on dashj pre-cutover", null)
        }
        if (!amount.isPositive) {
            return notBroadcast(operation, "non-positive amount", null)
        }
        val address = addressBase58.trim()
        if (address.isEmpty() || !addressValidSafe(address)) {
            return notBroadcast(operation, "malformed or wrong-network address", null)
        }

        // Preflight — nothing has been submitted if any of this fails.
        val walletIdHex = try {
            source.boundWalletIdOrNull()
                ?: return notBroadcast(operation, "app wallet not bound to the SDK", null)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast(operation, "SDK bootstrap/bind lookup failed", t)
        }

        // Evidence gate — the same rule as shieldFromWallet, via the SAME
        // helper: engine running + filter scan caught up to the chain tip
        // (SDK-only preconditions — see evaluateWalletFundingGate).
        // Shared with the debug settings status line via probeSendGate.
        val gate = probeSendGate()
        if (!gate.allowed) {
            return notBroadcast(operation, "$L1_FUNDING_GATE_CLOSED_REASON: ${gate.reason}", null)
        }

        // Send-all guards, receival sweeps and floor. Order matters:
        // 1. the app-locked-output guards (pre-everything, fail closed);
        // 2. beforeBroadcast() — the call site's pre-send conditions may
        //    throw (LeftoverBalanceException), and a throw must leave
        //    NOTHING broadcast, the receival self-sweeps included;
        // 3. sweep ALL receival accounts into BIP44 (MAX completeness);
        // 4. the floor read — AFTER the sweeps so it covers the swept
        //    funds. Every failure through step 4 is NotBroadcast by
        //    construction (the payment was never built; sweeps are
        //    self-sends), never Ambiguous.
        if (emptyWallet) {
            // FAIL-CLOSED GUARD (funds-critical): the drain selects EVERY
            // spendable UTXO and the FFI has no exclusion API (see the
            // [hasAppLockedSpendableOutputs] KDoc) — with any app-locked
            // output present (CrowdNode) it would spend protected funds.
            // Checked BEFORE any build/balance call; a check failure also
            // blocks. Real fix: an upstream SDK UTXO lock/exclusion API
            // (iOS's add_inputs_from_outpoints binding is the porting
            // candidate).
            if (hasProtectedOutputs(operation)) {
                log.warn(
                    "SDK {}: wallet has app-locked outputs (CrowdNode); send-all via the SDK would " +
                        "spend them — blocked until the SDK exposes UTXO exclusion",
                    operation
                )
                return notBroadcast(
                    operation,
                    "wallet has app-locked outputs (CrowdNode); send-all via the SDK would spend " +
                        "them — blocked until the SDK exposes UTXO exclusion",
                    null
                )
            }
        }

        // Call-site pre-send conditions (may throw, e.g. LeftoverBalanceException) —
        // deliberately outside the classification try: nothing broadcast yet
        // (the receival sweeps below run only after this passes) and the dashj
        // path surfaces the same throw the same way.
        beforeBroadcast()

        val sendAllFloor = if (emptyWallet) {
            // MAX-SEND COMPLETENESS (funds-visible-but-stranded bug): the
            // BIP44 drain cannot see DashPay receival-account funds and
            // self-limits without failing, so the plain-send shortfall
            // fallback never fires on a max send — sweep ALL sweepable
            // receival accounts into BIP44 up front (one self-send per
            // account, never unioned) and await their spendability; only
            // then read the floor and drain. No-op (one balance read) when
            // no receival account holds sweepable funds.
            try {
                source.sweepReceivalAccountsForSendAll(walletIdHex)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                return notBroadcast(
                    operation,
                    "receival-account sweep for the send-all failed — funds are safe (in their " +
                        "receival accounts, or consolidating into BIP44 as self-sends); retry the send",
                    t
                )
            }
            // Send-all floor (iOS-validated max pattern): spendable − reserve,
            // read AFTER the sweeps so the floor covers the swept funds —
            // still strictly pre-broadcast of the payment, so a read failure
            // stays NotBroadcast by construction, never Ambiguous.
            try {
                sendAllFloorDuffs(source.spendableBalanceDuffs(walletIdHex))
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                return notBroadcast(operation, "SDK spendable-balance read failed", t)
            }
        } else {
            null
        }

        // The single broadcast attempt. (The send-all shortfall retry is not
        // a second broadcast: an [isSendAllShortfall] throw is a provably
        // pre-broadcast build failure — see the predicate's KDoc.)
        return try {
            val txidHex = if (sendAllFloor != null) {
                // ONE core-send-lock acquisition across BOTH drain attempts:
                // a concurrent plain send serializes on the same mutex, so
                // it can no longer slip between the floor attempt and the
                // adjust-down retry and change the drained balance.
                // Deadlock-free: sendAllToAddress is lock-free by contract
                // and the plain-send path (which re-acquires the mutex
                // SDK-side) is never invoked inside this block.
                source.withCoreSendLock(walletIdHex) {
                    try {
                        source.sendAllToAddress(walletIdHex, address, sendAllFloor)
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        if (!isSendAllShortfall(t)) throw t
                        // Adjust down: fee exceeded the reserve. Retry ONCE with
                        // the engine fully authoritative (deliverable = total − fee).
                        log.info(
                            "SDK {}: floor {} duffs not deliverable at fee; retrying engine-authoritatively",
                            operation, sendAllFloor, t
                        )
                        source.sendAllToAddress(walletIdHex, address, 1L)
                    }
                }
            } else {
                source.sendToAddress(walletIdHex, address, amount.duffs)
            }
            log.info("SDK {}: broadcast {} duffs to {}…, txid {}", operation, amount.duffs, address.take(8), txidHex)
            // Parity-decider guard, never affects the send result.
            runCatching { onSelfSpendBroadcast() }
                .onFailure { log.warn("failed to record the self-spend marker", it) }
            // Phase 5c.2 (DEBUG builds — see the hook's KDoc): bridge the
            // SDK tx into the dashj wallet, fire-and-forget.
            runCatching { bridgeAfterBroadcast(txidHex) }
                .onFailure { log.warn("failed to launch the bridged-tx commit for {}", txidHex, it) }
            SdkWriteResult.Broadcast(txidHex)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            val classified = classifyCoreSendFailure(t)
            when (classified) {
                is SdkWriteResult.NotBroadcast ->
                    log.warn("SDK {} rejected pre-broadcast; falling back to dashj", operation, t)
                is SdkWriteResult.Ambiguous ->
                    log.error(
                        "SDK {} outcome unconfirmed — the transaction MAY be on the network and " +
                            "its inputs stay reserved; surfacing the error WITHOUT retrying via dashj",
                        operation,
                        t
                    )
                is SdkWriteResult.Broadcast -> Unit // unreachable
            }
            classified
        }
    }

    /**
     * Drain the DIP-9 CoinJoin account (`m/9'/coin'/4'/0'`) to
     * [ownAddressBase58] — the "combine my previously mixed funds into the
     * spendable balance" half of the post-upgrade mixed-funds migration.
     *
     * SINGLE-ACCOUNT, by construction: the builder is pointed at
     * `AccountType.COIN_JOIN` index 0 and key-wallet seeds its input set from
     * that account's UTXO map alone ([CoreSendAllNative.buildSignBroadcastDrainCoinJoin]).
     * BIP44 coins are never co-spent, so no privacy domain is crossed on the
     * INPUT side. The transaction does de-mix — it links the mixed coins to
     * the destination — which the calling UI must state plainly.
     *
     * [ownAddressBase58] MUST be an address of THIS wallet's unmixed BIP44
     * account (callers derive it via `WalletData.freshReceiveAddress()`); the
     * only validation possible here is network/format.
     *
     * The floor is `1` duff: the engine overwrites the single output with
     * `total − fee`, and there is no deliverable to protect on a self-spend.
     * An empty account therefore fails pre-broadcast with the engine's
     * "Insufficient funds" build error — [SdkWriteResult.NotBroadcast], no
     * funds moved. Same classification contract as [sendToAddress]: never
     * retry a [SdkWriteResult.Ambiguous] result.
     */
    suspend fun drainCoinJoinAccountTo(ownAddressBase58: String): SdkWriteResult<String> {
        val operation = "l1DrainCoinJoin"
        if (!isEnabled()) return SdkWriteResult.NotBroadcast("flag off")
        val address = ownAddressBase58.trim()
        if (address.isEmpty() || !addressValidSafe(address)) {
            return notBroadcast(operation, "malformed or wrong-network address", null)
        }

        val walletIdHex = try {
            source.boundWalletIdOrNull()
                ?: return notBroadcast(operation, "app wallet not bound to the SDK", null)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast(operation, "SDK bootstrap/bind lookup failed", t)
        }

        val gate = probeSendGate()
        if (!gate.allowed) {
            return notBroadcast(operation, "$L1_FUNDING_GATE_CLOSED_REASON: ${gate.reason}", null)
        }

        // Fail-closed lock guard, scoped to the account this drain touches.
        val hasLockedCoinJoinOutputs = try {
            hasAppLockedCoinJoinOutputs()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("SDK {}: CoinJoin locked-output preflight failed; blocking (fail closed)", operation, t)
            true
        }
        if (hasLockedCoinJoinOutputs) {
            return notBroadcast(
                operation,
                "wallet has app-locked outputs on the CoinJoin account; the drain would spend them",
                null
            )
        }

        return try {
            val txidHex = source.withCoreSendLock(walletIdHex) {
                source.drainCoinJoinToAddress(walletIdHex, address, COIN_JOIN_DRAIN_FLOOR_DUFFS)
            }
            log.info("SDK {}: drained the CoinJoin account to {}…, txid {}", operation, address.take(8), txidHex)
            runCatching { onSelfSpendBroadcast() }
                .onFailure { log.warn("failed to record the self-spend marker", it) }
            runCatching { bridgeAfterBroadcast(txidHex) }
                .onFailure { log.warn("failed to launch the bridged-tx commit for {}", txidHex, it) }
            SdkWriteResult.Broadcast(txidHex)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            when (val classified = classifyCoreSendFailure(t)) {
                is SdkWriteResult.NotBroadcast -> {
                    log.warn("SDK {} rejected pre-broadcast; nothing spent", operation, t)
                    classified
                }
                else -> {
                    log.error(
                        "SDK {} outcome unconfirmed — the drain MAY be on the network; NOT retrying",
                        operation, t
                    )
                    classified
                }
            }
        }
    }

    /**
     * BIP70/BIP270, post-cutover (issue #1520 Phase 1B item 1): build and
     * sign a multi-recipient payment with its inputs RESERVED, without
     * broadcasting. The caller owns the route decision (it only enters
     * this path once [cutoverCommitted] — there is no dashj fallback), so
     * unlike [sendToAddress] this THROWS on every failure instead of
     * returning [SdkWriteResult]: a build never broadcasts, so nothing
     * needs Ambiguous classification and the throw is always safe to
     * surface as "payment not made". The reservation exists only once this
     * returns; follow with exactly one of [broadcastDeferredPayment]
     * (merchant acked) or [releaseDeferredPayment] (abandoned/nacked).
     */
    suspend fun buildDeferredPayment(recipients: List<Pair<String, Long>>): SdkDeferredPayment {
        check(recipients.isNotEmpty()) { "BIP70 payment has no outputs" }
        val trimmed = recipients.map { (address, amountDuffs) ->
            check(amountDuffs > 0) { "BIP70 output amount must be positive, got $amountDuffs" }
            val clean = address.trim()
            check(clean.isNotEmpty() && addressValidSafe(clean)) {
                "BIP70 output address is malformed or for the wrong network"
            }
            clean to amountDuffs
        }
        val walletIdHex = checkNotNull(source.boundWalletIdOrNull()) {
            "app wallet not bound to the SDK"
        }
        val gate = probeSendGate()
        check(gate.allowed) { "L1 funding gate closed: ${gate.reason}" }
        val payment = source.buildDeferredPayment(walletIdHex, trimmed)
        log.info(
            "SDK l1DeferredBuild: built {} ({} recipient(s), fee {} duffs), inputs reserved",
            payment.txidHex, trimmed.size, payment.feeDuffs
        )
        return payment
    }

    /**
     * FAIL-CLOSED protected-output preflight, shared by every path that
     * sweeps (or all but sweeps) the wallet: true when the wallet holds any
     * app-locked output — CrowdNode account locks in the held dashj wallet,
     * or seam-registered locks on SDK-only txs that the dashj check cannot
     * see. The FFI has no UTXO-exclusion API, so a sweep-scale build would
     * spend protected funds; a check failure blocks too.
     */
    private fun hasProtectedOutputs(operation: String): Boolean {
        val hasLockedOutputs = try {
            hasAppLockedSpendableOutputs()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("SDK {}: app-locked-output preflight failed; blocking (fail closed)", operation, t)
            true
        }
        val hasSeamLockedOutputs = try {
            seamOutputLockRegistry.hasAnyLocks()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("SDK {}: seam output-lock registry read failed; blocking (fail closed)", operation, t)
            true
        }
        return hasLockedOutputs || hasSeamLockedOutputs
    }

    /**
     * The largest amount a MAYACHAIN deposit can pay a vault right now: what
     * a DRAIN of the funding account delivers, read off the engine.
     *
     * Nothing here is estimated and nothing is withheld. A max deposit IS a
     * drain, so this builds one — same builder, same three options, the
     * wallet's own address standing in for the vault — and reads the
     * deliverable amount the engine reports, then releases the reservation.
     * The engine sets that output to `total inputs − fee` itself, with this
     * memo's bytes priced in and no change, so the quote and the deposit that
     * follows perform the identical computation and cannot disagree.
     *
     * That equality is the point. The retired model subtracted a guessed fee
     * and a change-headroom constant from the wallet-wide spendable balance,
     * which could only ever approximate what the deposit would really pay. A
     * quote that comes in OVER the real deliverable makes the deposit pay the
     * vault less than quoted, and NEAR Intents refuses under-delivery (~1h
     * wait, then a refund minus 0.001 DASH). Do not reintroduce a headroom or
     * reserve constant here: it would reopen exactly that gap.
     *
     * [memoSizeBytes] defaults to the 80-byte OP_RETURN ceiling, so a shorter
     * real memo can only leave the real transaction smaller and its
     * deliverable no lower than quoted.
     *
     * Returns 0 when no drain is fundable at all — the engine's typed
     * refusal when the inputs cannot cover the fee, which is precisely
     * "nothing depositable" (the caller surfaces "not enough funds" rather
     * than quoting a negative amount). Throws like [buildDeferredMayaDeposit]
     * on gate/bind failures.
     */
    suspend fun maxMayaDepositDuffs(memoSizeBytes: Int = MAX_MAYA_MEMO_BYTES): Long {
        require(memoSizeBytes in 1..MAX_MAYA_MEMO_BYTES) {
            "memoSizeBytes must be 1..$MAX_MAYA_MEMO_BYTES, got $memoSizeBytes"
        }
        val walletIdHex = checkNotNull(source.boundWalletIdOrNull()) {
            "app wallet not bound to the SDK"
        }
        val gate = probeSendGate()
        check(gate.allowed) { "L1 funding gate closed: ${gate.reason}" }
        // FAIL-CLOSED (funds-critical): a max deposit drains the funding
        // account outright, so coin selection will reach app-locked outputs
        // (CrowdNode) — which [spendableBalanceDuffs] deliberately INCLUDES
        // and the FFI cannot be told to exclude. Same guard the send-all
        // drain applies, for the same reason: refuse to quote rather than
        // sweep protected funds into a swap. A partial (non-max) deposit
        // keeps the ordinary send's exposure.
        //
        // [buildDeferredMayaDeposit] enforces this too, for every drain caller.
        // Do NOT delete this copy as redundant: the probe below runs inside a
        // catch-all that converts any failure into a quote of 0, so relying on
        // the primitive alone would turn "refuse, you hold locked funds" into
        // a silent "your maximum is 0".
        check(!hasProtectedOutputs("l1MayaMaxDeposit")) {
            "wallet has app-locked outputs (CrowdNode); a max swap deposit would spend them"
        }
        // MEASURE BY DRAINING, don't estimate. A max deposit IS a drain, so
        // build one and read what the engine says it delivers: every BIP44 UTXO
        // selected, this memo's bytes priced into the fee, no change. That is
        // the same computation the real deposit will perform, so quote and
        // deposit cannot disagree — which subtracting a guessed fee and a
        // change-headroom constant from the wallet-wide spendable could not
        // promise. The probe is built to an OWN address; the destination does
        // not change the fee (same P2PKH output size as a vault), and it is
        // released immediately either way.
        val probeAddress = checkNotNull(source.unusedExternalAddress(walletIdHex)) {
            "no SDK address available to size a Maya deposit"
        }
        val probe = try {
            source.buildDeferredMayaDeposit(
                walletIdHex,
                probeAddress,
                0L, // ignored under a drain
                ByteArray(memoSizeBytes),
                drain = true
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // The engine refuses a drain whose inputs cannot cover the fee
            // (typed InsufficientFunds), which is exactly "nothing depositable"
            // — the floor the probe-reserve constant used to approximate.
            log.info("SDK l1MayaMaxDeposit: no drain is fundable; max deposit 0 ({})", t.message)
            return 0L
        }
        val max = try {
            probe.deliverableDuffs
        } finally {
            // NonCancellable: the probe holds a real engine reservation, and
            // leaving it to the TTL sweep would make the very next real build
            // fail to fund.
            withContext(NonCancellable) { releaseDeferredPayment(probe) }
        }
        log.info(
            "SDK l1MayaMaxDeposit: drain-measured max deposit {} duffs (fee {}, {}-byte memo)",
            max, probe.feeDuffs, memoSizeBytes
        )
        return max.coerceAtLeast(0L)
    }

    /**
     * [buildDeferredPayment] in the MAYACHAIN deposit shape (vault VOUT0,
     * [memo] as a zero-value OP_RETURN VOUT1, change back to VIN0's
     * address VOUT2, no reordering) — the Maya/SwapKit swap-send build.
     * Same gate and reservation contract; the caller verifies the shape
     * from [SdkDeferredPayment.rawTxBytes] and then broadcasts via
     * [broadcastDeferredPayment] or abandons via [releaseDeferredPayment].
     * [memo] must fit the 80-byte OP_RETURN standardness limit — checked
     * here (and re-checked engine-side) BEFORE anything is reserved.
     *
     * Under [drain] this refuses outright when the wallet holds app-locked
     * outputs (CrowdNode), the same fail-closed guard the send-all drain
     * applies — see the check in the body for why it lives here rather than
     * at the call site.
     */
    suspend fun buildDeferredMayaDeposit(
        vaultAddressBase58: String,
        vaultDuffs: Long,
        memo: ByteArray,
        drain: Boolean = false
    ): SdkDeferredPayment {
        // A drain has the engine compute the vault output, so no amount is
        // supplied; every other build must name a positive one.
        check(drain || vaultDuffs > 0) { "Maya vault amount must be positive, got $vaultDuffs" }
        val vault = vaultAddressBase58.trim()
        check(vault.isNotEmpty() && addressValidSafe(vault)) {
            "Maya vault address is malformed or for the wrong network"
        }
        check(memo.size in 1..MAX_MAYA_MEMO_BYTES) {
            "Maya memo must be 1..$MAX_MAYA_MEMO_BYTES bytes, got ${memo.size}"
        }
        val walletIdHex = checkNotNull(source.boundWalletIdOrNull()) {
            "app wallet not bound to the SDK"
        }
        val gate = probeSendGate()
        check(gate.allowed) { "L1 funding gate closed: ${gate.reason}" }
        // FAIL-CLOSED GUARD (funds-critical), drain only: a drain selects
        // every spendable UTXO and the FFI has no exclusion API, so with any
        // app-locked output present (CrowdNode) it would sweep protected funds
        // into a vault — irreversibly, once broadcast. Enforced HERE, in the
        // primitive, rather than trusting the caller to have measured first:
        // [maxMayaDepositDuffs] does check, and [MayaBlockchainApiImpl] does
        // call it, but that is a call-site convention and a convention is one
        // refactor away from being skipped. A partial (non-max) deposit is not
        // guarded — it keeps the ordinary send's exposure, unchanged.
        //
        // [maxMayaDepositDuffs] keeps its own copy of this check deliberately:
        // its probe runs inside a catch-all that turns any failure into a
        // quote of 0, which would silently swallow this refusal.
        if (drain) {
            check(!hasProtectedOutputs("l1DeferredMayaBuild")) {
                "wallet has app-locked outputs (CrowdNode); a max swap deposit would spend them"
            }
        }
        val payment = source.buildDeferredMayaDeposit(walletIdHex, vault, vaultDuffs, memo, drain)
        log.info(
            "SDK l1DeferredMayaBuild: built {} ({} duffs to the vault{}, {}-byte memo, fee {} duffs), inputs reserved",
            payment.txidHex,
            if (drain) payment.deliverableDuffs else vaultDuffs,
            if (drain) " by DRAIN (engine-computed)" else "",
            memo.size,
            payment.feeDuffs
        )
        return payment
    }

    /**
     * Broadcast [payment]'s already-signed tx, consuming its reservation —
     * the "merchant acked" arm of a BIP70 flow. One attempt, classified by
     * [classifyDeferredBroadcastFailure]; the token-error refusals (stale /
     * consumed / wallet mismatch) are definitively pre-network →
     * [SdkWriteResult.NotBroadcast]. Fires the self-spend parity marker on
     * success like every other SDK broadcast. Deliberately NO bridge hook
     * here — the BIP70 caller bridges synchronously with the raw bytes it
     * already holds.
     */
    suspend fun broadcastDeferredPayment(payment: SdkDeferredPayment): SdkWriteResult<String> {
        val operation = "l1DeferredBroadcast"
        val walletIdHex = try {
            source.boundWalletIdOrNull()
                ?: return notBroadcast(operation, "app wallet not bound to the SDK", null)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast(operation, "SDK bootstrap/bind lookup failed", t)
        }
        return try {
            val txidHex = source.broadcastDeferredPayment(walletIdHex, payment)
            log.info("SDK {}: broadcast deferred tx {}", operation, txidHex)
            runCatching { onSelfSpendBroadcast() }
                .onFailure { log.warn("failed to record the self-spend marker", it) }
            SdkWriteResult.Broadcast(txidHex)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            val classified = classifyDeferredBroadcastFailure(t)
            when (classified) {
                is SdkWriteResult.NotBroadcast ->
                    log.warn("SDK {} refused pre-network for {}", operation, payment.txidHex, t)
                is SdkWriteResult.Ambiguous ->
                    log.error(
                        "SDK {} outcome unconfirmed for {} — the tx MAY be on the network",
                        operation, payment.txidHex, t
                    )
                is SdkWriteResult.Broadcast -> Unit // unreachable
            }
            classified
        }
    }

    /**
     * Release [payment]'s reservation without broadcasting — the
     * abandoned/nacked arm of a BIP70 flow. Best-effort and contained: a
     * release failure only means the inputs stay reserved until the
     * engine's TTL sweep (or the reservation object's GC backstop)
     * reclaims them, so it is logged, never thrown.
     */
    suspend fun releaseDeferredPayment(payment: SdkDeferredPayment) {
        try {
            val walletIdHex = source.boundWalletIdOrNull()
            if (walletIdHex == null) {
                log.warn(
                    "SDK l1DeferredRelease: wallet not bound; reservation for {} falls to the TTL sweep",
                    payment.txidHex
                )
                return
            }
            source.releaseDeferredPayment(walletIdHex, payment)
            log.info("SDK l1DeferredRelease: released the reservation for {}", payment.txidHex)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn(
                "SDK l1DeferredRelease failed for {} — the engine's TTL sweep reclaims the inputs",
                payment.txidHex, t
            )
        }
    }

    /**
     * The BIP70 `Payment.refund_to` source, post-cutover: the lowest
     * unused external address from the SDK's persisted address pool
     * ([SdkL1SendSource.unusedExternalAddress]) — no dashj keychain read.
     * Contained: null (⇒ the caller omits refund_to, which BIP70 makes
     * optional) when the wallet is unbound, the pool rows are missing, or
     * the read fails.
     */
    suspend fun refundAddressOrNull(): String? = try {
        source.boundWalletIdOrNull()?.let { source.unusedExternalAddress(it) }
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        log.warn("SDK refund-address read failed; the Payment message will omit refund_to", t)
        null
    }

    /** [isValidAddress] with failures contained (a throw must not escape a preflight). */
    private fun addressValidSafe(address: String): Boolean = try {
        isValidAddress(address)
    } catch (e: Exception) {
        false
    }

    private fun safeProgress(): ShadowSyncProgress = try {
        l1Progress()
    } catch (e: Exception) {
        log.warn("failed to read the SDK L1 sync progress; send gate stays closed", e)
        ShadowSyncProgress.IDLE
    }

    private fun notBroadcast(operation: String, reason: String, cause: Throwable?): SdkWriteResult.NotBroadcast {
        log.info("SDK {} not attempted ({}); using dashj", operation, reason, cause)
        return SdkWriteResult.NotBroadcast(reason, cause)
    }

    private suspend fun isEnabled(): Boolean = try {
        dashPayConfig.get(DashPayConfig.USE_KOTLIN_SDK_L1_SEND) == true || cutoverCommitted()
    } catch (e: Exception) {
        log.warn("failed to read USE_KOTLIN_SDK_L1_SEND; keeping dashj path", e)
        false
    }

    /**
     * Phase 5d: has the cutover been COMMITTED (persisted state CUT_OVER or
     * SETTLED — the states where [dashjEngineMayStart] is false)? Post-commit
     * the dashj L1 engine is held, so:
     * - this service is enabled regardless of the soak flag (see [isEnabled]),
     * - callers must FAIL CLOSED on [SdkWriteResult.NotBroadcast] instead of
     *   falling back to dashj — dashj's peergroup is dead, and a "fallback"
     *   send would commit a tx that silently queues until a rollback
     *   resurrects the engine and broadcasts it long after the user was told
     *   the send failed.
     * Contained: a config read failure reads as NOT committed (dashj rules,
     * today's behavior).
     */
    suspend fun cutoverCommitted(): Boolean = try {
        !dashjEngineMayStart(CutoverState.fromStored(dashPayConfig.get(DashPayConfig.CUTOVER_STATE)))
    } catch (e: Exception) {
        log.warn("failed to read the cutover state; treating as not committed", e)
        false
    }

    companion object {
        private val log = LoggerFactory.getLogger(SdkL1SendService::class.java)

        /**
         * Deliver-at-least floor for the CoinJoin drain. `1` duff: the engine
         * overwrites the single output with `total − fee` and the destination
         * is the user's own wallet, so there is no deliverable to protect —
         * unlike the user-facing send-all, which reserves for the fee to keep
         * the promised amount intact.
         */
        private const val COIN_JOIN_DRAIN_FLOOR_DUFFS = 1L

        /**
         * OP_RETURN relay-standardness limit — the ceiling for a Maya swap
         * memo, matching Dash Core's `-datacarriersize` default (and the
         * engine's `DEFAULT_MAX_OP_RETURN_BYTES`, which re-checks).
         */
        const val MAX_MAYA_MEMO_BYTES = 80
    }
}
