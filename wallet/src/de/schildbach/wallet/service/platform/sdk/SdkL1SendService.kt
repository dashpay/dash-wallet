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
    // NOTE: the typed `TransactionBuild` (code 32) arm was dropped on the
    // v4.2-dev SDK line — its owning PRs (#4310/#4311) closed unmerged, so the
    // code lapsed. A build refusal now falls through to the WalletOperation
    // message-prefix rule below, the same path used before the typed arm
    // existed; behavior is unchanged for the reachable case.
    //
    // Signing failure: the merged line surfaces this as `SigningKeyUnavailable`
    // (code 31) — the signer holds no usable private key for a requested public
    // key (Keystore/Keychain mnemonic locked or missing). Nothing reached the
    // wire and the IDENTICAL send may be resubmitted once the signer is
    // unlocked. Keyed by [L1_SIGNER_LOCKED_REASON] so the send UI surfaces
    // "unlock to continue", never a hard payment failure.
    t is DashSdkError.PlatformWallet.SigningKeyUnavailable ->
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
 * FALLBACK fee reserve backing the send-all floor, in duffs — used only
 * when the pooled-UTXO enumeration behind [sendAllFeeReserveDuffs] is
 * unavailable. The drain's fee at the builder's default rate
 * (1000 duffs/kB, `FeeRate::normal()`) is `~44 + 148·n_inputs` duffs, so
 * 10 000 covers a drain of ~67 inputs. When a wallet exceeds what the
 * reserve covers, the floor attempt fails pre-broadcast with the engine's
 * insufficient-at-fee shortfall and [SdkL1SendService] retries once
 * engine-authoritatively (floor 1) — the adjust-down half of the pattern.
 */
internal const val SEND_ALL_FEE_RESERVE_DUFFS = 10_000L

/**
 * Size-based fee reserve for the send-all floor, in duffs — the iOS max-send
 * model (dashwallet-ios#928), replacing the flat [SEND_ALL_FEE_RESERVE_DUFFS]
 * that under-reserved for many-input wallets (a pooled `ALL_SPENDABLE` drain
 * selects EVERY spendable UTXO across BIP44 + BIP32 + the DashPay receival
 * accounts, so the input count — not a constant — drives the fee).
 *
 * Model: `10 + n·148 + 68` bytes (tx skeleton + per-P2PKH-input + the single
 * drain output) at 1 duff/byte, plus a 50% safety margin. [pooledUtxoCount]
 * is the UNSPENT output count over exactly the accounts `ALL_SPENDABLE`
 * pools ([SdkL1SendSource.pooledSpendableUtxoCount]); null (enumeration
 * unavailable/failed) falls back to the flat constant.
 */
internal fun sendAllFeeReserveDuffs(pooledUtxoCount: Int?): Long {
    if (pooledUtxoCount == null || pooledUtxoCount < 0) return SEND_ALL_FEE_RESERVE_DUFFS
    val sizeBytes = 10L + pooledUtxoCount.toLong() * 148L + 68L
    return sizeBytes * 3L / 2L
}

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
 * The fee reserve to withhold from a MAX Maya deposit, mirroring the
 * shielded max-shield reserve
 * ([de.schildbach.wallet.ui.shielded.assetLockMaxFeeReserve]) rather than
 * inventing a second sizing rule.
 *
 * A max deposit selects (essentially) every spendable UTXO, so the fee is
 * bounded by transaction size: ~148 vbytes per input, plus the deposit's own
 * outputs — vault (~34) + the OP_RETURN carrying [memoSizeBytes] + change
 * (~34) + overhead — doubled as a safety margin. Unlike the shielded
 * formula's flat 300-byte allowance this can size the data carrier exactly,
 * because a Maya quote always knows its memo length.
 *
 * Over-reserving is LOSSLESS: the deposit is a fixed-amount send, so the
 * builder returns the unused remainder as change. Under-reserving is the
 * failing direction — the build comes up short at fee and is refused — so
 * [spendableUtxoCount] must be the POST-CUTOVER overlaid count
 * (`CutoverUiDataService`), never the held dashj wallet's frozen one, and the
 * result is clamped to a 1000-duff minimum so a degenerate count still
 * reserves something meaningful.
 *
 * ## Why a reserve rather than a drain
 *
 * A drain (`SelectionStrategy.ALL`) produced a transaction with NO
 * wallet-owned output — vault, data carrier, no change. Compact block filters
 * match on wallet script pubkeys only, so such a transaction is never matched
 * in a block, its context never reaches `CONTEXT_IN_BLOCK`, and the wallet
 * counts the spent inputs as spendable forever (mainnet `a5c99aec…`,
 * `1f608a9a…`). Leaving change restores a wallet-owned output, so the deposit
 * confirms and settles like any other send. Revisit once the SDK computes MAX
 * internally in the wallet engine — at which point the engine, not this
 * arithmetic, should own the amount.
 */
/**
 * The input count a MAX Maya reserve is sized for at minimum. Guards against a
 * frozen/stale post-cutover UTXO count under-reserving: over-reserving leaves a
 * little more behind as change, under-reserving refuses the deposit.
 */
internal const val MAYA_MAX_RESERVE_MIN_INPUTS = 64

internal fun mayaMaxFeeReserveDuffs(spendableUtxoCount: Int, memoSizeBytes: Int): Long {
    val inputBytes = spendableUtxoCount.coerceAtLeast(0).toLong() * 148L
    val outputBytes = 34L + memoSizeBytes.coerceAtLeast(0).toLong() + 11L + 34L + 10L
    return ((inputBytes + outputBytes) * 2L).coerceAtLeast(1000L)
}

/**
 * True iff [t] is the engine's insufficient-at-fee build failure — the ONE
 * failure the send-all path may retry with a lower floor. By construction
 * a subset of [classifyCoreSendFailure]'s NotBroadcast arms, so nothing was
 * broadcast and a single retry cannot double-pay.
 *
 * TYPED arm (v41int19, dashpay/platform#4329): the pooled atomic finalize
 * raises the selector shortfall as the DEDICATED
 * [DashSdkError.PlatformWallet.CoreInsufficientFunds] (rust
 * `CoreInsufficientFunds`/`CorePooledInsufficientFunds`, both FFI code 22) —
 * strictly while BUILDING, pre-broadcast. Without this arm the typed
 * shortfall would classify NotBroadcast but never trigger the adjust-down
 * retry, silently killing every max send whose fee exceeds the reserve.
 *
 * LEGACY arm (transition window): the v1 split surface wrapped the same
 * shortfall as WalletOperation with the `transaction build failed` FFI
 * prefix and key-wallet's "Insufficient funds" Display text
 * (`BuilderError::InsufficientFunds` / `SelectionError::InsufficientFunds`).
 */
internal fun isSendAllShortfall(t: Throwable): Boolean =
    t is DashSdkError.PlatformWallet.CoreInsufficientFunds ||
        (
            t is DashSdkError.PlatformWallet.WalletOperation &&
                t.message?.startsWith("transaction build failed") == true &&
                t.message?.contains("Insufficient funds") == true
            )

// ── Pooled (`ALL_SPENDABLE`) funding — account tags & pure parses ─────

/**
 * `AccountTypeTagFFI::DashpayReceivingFunds` — the numeric `typeTag` the
 * account-balance JSON (and the SDK Room `accounts.accountType` column,
 * same tag space) reports for a DashPay receiving-funds account (a
 * contact's payments to us). Value from rs-platform-wallet-ffi
 * `wallet_restore_types.rs` (`DashpayReceivingFunds = 12`), written into
 * the JSON as `e.type_tag as u8` by the JNI bridge's
 * `walletManagerAccountBalances` (rs-unified-sdk-jni `dashpay.rs`).
 * NOT 13 (`DashpaySendingFunds`) — a contact's watch-only external coins,
 * which `ALL_SPENDABLE` never pools.
 */
internal const val ACCOUNT_TYPE_TAG_DASHPAY_RECEIVING_FUNDS = 12

/**
 * `AccountTypeTagFFI::Standard` with `StandardAccountTypeTagFFI::Bip44` /
 * `::Bip32` — the `typeTag` / `standardTag` pairs the account-balance JSON
 * reports for the standard transparent funds accounts (values from
 * rs-platform-wallet-ffi `wallet_restore_types.rs`: `Standard = 0`,
 * `Bip44 = 0`, `Bip32 = 1`). Together with every
 * [ACCOUNT_TYPE_TAG_DASHPAY_RECEIVING_FUNDS] account these are EXACTLY the
 * accounts the engine's `ALL_SPENDABLE` funding pools
 * (`CoreAccountTypeFFI::AllSpendable`, dashpay/platform#4329) — CoinJoin
 * (tag 1) stays out as a separate privacy domain, watch-only (13) is not
 * signable.
 */
internal const val ACCOUNT_TYPE_TAG_STANDARD = 0
internal const val STANDARD_ACCOUNT_TAG_BIP44 = 0
internal const val STANDARD_ACCOUNT_TAG_BIP32 = 1

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
 * The POOLED spendable balance in duffs — the sum of `confirmed +
 * unconfirmed` over EXACTLY the accounts the engine's `ALL_SPENDABLE`
 * funding pools (dashpay/platform#4329):
 *
 *   BIP44 account 0 + BIP32 account 0 (`typeTag` 0, `standardTag` 0/1)
 *   + every DashPay receiving-funds account (`typeTag` 12)
 *
 * This is both the POST-CUTOVER "max sendable" display figure (what ONE
 * pooled drain can deliver before its own fee — no per-account sweep
 * headrooms anymore: the accounts are co-spent in a single transaction)
 * and the send-all floor's balance base. CoinJoin (tag 1) and watch-only
 * (tag 13) rows never count — `ALL_SPENDABLE` excludes them, which is
 * exactly why the wallet-wide native `balance()` (CoinJoin included)
 * overstates what a drain delivers. Null when the snapshot is
 * missing/malformed or has no BIP44 row (caller falls back to the
 * wallet-wide total).
 */
internal fun pooledSpendableDuffs(accountBalancesJson: String?): Long? {
    // The BIP44 row gate: a snapshot that cannot AFFIRM the main account
    // cannot quote anything — null, never 0.
    parseBip44SpendableDuffs(accountBalancesJson) ?: return null
    return try {
        val rows = org.json.JSONArray(accountBalancesJson!!)
        var totalDuffs = 0L
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val typeTag = row.optInt("typeTag", -1)
            val pooled = when (typeTag) {
                ACCOUNT_TYPE_TAG_STANDARD ->
                    row.optInt("standardTag", -1)
                        .let { it == STANDARD_ACCOUNT_TAG_BIP44 || it == STANDARD_ACCOUNT_TAG_BIP32 } &&
                        row.optInt("index", -1) == 0
                ACCOUNT_TYPE_TAG_DASHPAY_RECEIVING_FUNDS -> true
                else -> false
            }
            if (pooled) {
                totalDuffs += row.optLong("confirmed", 0L) + row.optLong("unconfirmed", 0L)
            }
        }
        totalDuffs
    } catch (e: Exception) {
        null
    }
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
     * [amountDuffs] to [addressBase58] from the SDK wallet's POOLED
     * `ALL_SPENDABLE` funding set — BIP44 + BIP32 + every DashPay
     * receival account, co-spent in ONE transaction with change returning
     * to BIP44 (the `sendToAddresses` default since v41int19,
     * dashpay/platform#4329; same seed/coins dashj spends). Returns the
     * broadcast txid as lowercase hex; throws on any failure
     * ([classifyCoreSendFailure] decides what the throw proves).
     */
    suspend fun sendToAddress(walletIdHex: String, addressBase58: String, amountDuffs: Long): String

    /**
     * The SDK wallet's POOLED spendable balance in duffs — `confirmed +
     * unconfirmed` over exactly the accounts `ALL_SPENDABLE` funds
     * ([pooledSpendableDuffs] over the account enumeration; wallet-wide
     * native `balance()` as the fallback when the enumeration is
     * unavailable — that coarser figure INCLUDES CoinJoin, which the drain
     * cannot deliver, so it may overstate and cost the adjust-down retry).
     * NOTE: the engine knows nothing of dashj's app-side
     * `Wallet.lockOutput` set (the CrowdNode account locks) — app-locked
     * outputs are INCLUDED in this figure and selectable by the SDK's coin
     * selection; that mismatch is exactly why [SdkL1SendService] refuses
     * the drain while any app-locked spendable output exists. Feeds
     * [sendAllFloorDuffs]. Default throws: only the production source (and
     * fakes that exercise send-all) need it.
     */
    suspend fun spendableBalanceDuffs(walletIdHex: String): Long =
        throw UnsupportedOperationException("send-all not supported by this source")

    /**
     * The number of UNSPENT transaction outputs across exactly the
     * accounts `ALL_SPENDABLE` pools (BIP44 + BIP32 at the funding index,
     * every DashPay receival account — never CoinJoin, never watch-only),
     * for the size-based send-all fee reserve ([sendAllFeeReserveDuffs]).
     * Null when the enumeration is unavailable or fails (the caller falls
     * back to the flat reserve) — a read failure must never fail the send.
     * Default null: sources without a TXO store.
     */
    suspend fun pooledSpendableUtxoCount(walletIdHex: String): Int? = null

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
     * Build, sign and broadcast a SEND-ALL (drain) of the POOLED
     * `ALL_SPENDABLE` funding set (BIP44 + BIP32 + every DashPay receival
     * account — ONE transaction; CoinJoin stays out) to [addressBase58]:
     * every spendable input, one output worth `total − fee`
     * (engine-computed), no change — `SelectionStrategy::All` via the
     * bound `coreTxBuilderSetSelectionStrategy` knob ([CoreSendAllNative]).
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
        memo: ByteArray
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
    private val service: DashSdkService
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
         * Decode a lowercase/uppercase hex wallet id (the manager's map key)
         * into the 32-byte form `PlatformWalletManager.accountBalances`
         * takes. Throws on malformed input — contained by the callers'
         * fall-back-to-the-coarser-figure catches.
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
        // builder defaults for fee rate / selection / change handling, signed
        // via the manager's mnemonic resolver — no private key crosses the
        // boundary. Since v41int19 (dashpay/platform#4329) sendToAddresses
        // defaults to the POOLED `ALL_SPENDABLE` funding set — BIP44 + BIP32
        // + every DashPay receival account co-spent in ONE transaction, change
        // to BIP44 — so the old N+1 receival-sweep fallback (sweep each
        // contact account into BIP44, then resend) is gone: a shortfall now
        // means the WHOLE pooled set cannot cover the payment, and the typed
        // CoreInsufficientFunds propagates to classifyCoreSendFailure
        // (NotBroadcast — the usual insufficient-funds surface).
        return wallet.sendToAddresses(
            recipients = listOf(addressBase58 to amountDuffs),
            network = toSdkNetwork(Constants.NETWORK_PARAMETERS),
            coreSignerHandle = manager.mnemonicResolverHandle
        )
    }

    override suspend fun spendableBalanceDuffs(walletIdHex: String): Long {
        val manager = manager()
        // Pooled figure first: `confirmed + unconfirmed` over exactly the
        // accounts the ALL_SPENDABLE drain funds (BIP44 + BIP32 + receival —
        // never CoinJoin). The wallet-wide native balance() INCLUDES CoinJoin,
        // so it overstates what the drain can deliver whenever mixed funds
        // exist; keep it only as the fallback when the account enumeration is
        // unavailable (the floor is then loose and the adjust-down retry
        // absorbs the difference).
        val pooledDuffs = try {
            pooledSpendableDuffs(manager.accountBalances(hexToBytes(walletIdHex)))
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("SDK l1Send: pooled balance enumeration failed; falling back to the wallet-wide figure", t)
            null
        }
        if (pooledDuffs != null) return pooledDuffs
        val wallet = checkNotNull(manager.wallets.value[walletIdHex]) { "SDK wallet not loaded" }
        val balance = wallet.balance()
        return balance.confirmed + balance.unconfirmed
    }

    override suspend fun pooledSpendableUtxoCount(walletIdHex: String): Int? {
        // Room mirror read (the SDK persists every TXO with its owning
        // account): count UNSPENT outputs over exactly the accounts
        // ALL_SPENDABLE pools. Contained — null on any failure, the caller
        // falls back to the flat fee reserve.
        return try {
            service.ensureStarted()
            val database = service.databaseOrNull() ?: return null
            val walletId = decodeHexOrNull(walletIdHex, walletIdHex.length / 2) ?: return null
            withContext(Dispatchers.IO) {
                database.openHelper.readableDatabase.query(
                    androidx.sqlite.db.SimpleSQLiteQuery(
                        // Pooled set: BIP44 + BIP32 at the funding index
                        // (accountType 0, standardTag 0/1, accountIndex 0) plus
                        // every DashPay receiving account (accountType 12) —
                        // never CoinJoin (1), never watch-only (13). Rows whose
                        // account pointer is missing (a.id IS NULL — the brief
                        // insert window) COUNT: over-counting only enlarges the
                        // fee reserve, which lowers the floor — safe for a
                        // drain, whose deliverable the engine computes anyway.
                        "SELECT COUNT(*) FROM txos t LEFT JOIN accounts a ON t.accountId = a.id " +
                            "WHERE t.walletId = ? AND t.isSpent = 0 AND (" +
                            "a.id IS NULL " +
                            "OR (a.accountType = $ACCOUNT_TYPE_TAG_STANDARD " +
                            "AND a.standardTag IN ($STANDARD_ACCOUNT_TAG_BIP44, $STANDARD_ACCOUNT_TAG_BIP32) " +
                            "AND a.accountIndex = 0) " +
                            "OR a.accountType = $ACCOUNT_TYPE_TAG_DASHPAY_RECEIVING_FUNDS)",
                        arrayOf<Any?>(walletId)
                    )
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else null }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("SDK l1SendAll: pooled UTXO count read failed; using the flat fee reserve", t)
            null
        }
    }

    override suspend fun buildDeferredPayment(
        walletIdHex: String,
        recipients: List<Pair<String, Long>>
    ): SdkDeferredPayment {
        val manager = manager()
        val wallet = checkNotNull(manager.wallets.value[walletIdHex]) { "SDK wallet not loaded" }
        // Same call shape as sendToAddresses (builder defaults, mnemonic-
        // resolver signing) minus the broadcast: the SDK returns the signed
        // bytes with the inputs reserved behind the object's token. Like the
        // immediate send, the accountType default is the POOLED ALL_SPENDABLE
        // set since v41int19 — a BIP70 payment funds from BIP44 + BIP32 +
        // the DashPay receival accounts in ONE reserved transaction.
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
        memo: ByteArray
    ): SdkDeferredPayment {
        val manager = manager()
        val wallet = checkNotNull(manager.wallets.value[walletIdHex]) { "SDK wallet not loaded" }
        // Same deferred-build primitive as buildDeferredPayment, plus the
        // three MAYACHAIN builder options. The OP_RETURN is appended after
        // the vault recipient SDK-side, so preserveOutputOrder yields the
        // documented vault=VOUT0 / memo=VOUT1 shape; an over-long memo
        // throws pre-reservation.
        // Every deposit names its own amount, max included: a MAX deposit is
        // `spendable − reserve` ([maxMayaDepositDuffs]), an ordinary
        // fixed-amount send. No SelectionStrategy override, so the build keeps
        // a change output — which is what lets compact block filters match it
        // and the transaction settle (see [mayaMaxFeeReserveDuffs] for why a
        // changeless drain could not). Reinstate the drain only when the SDK
        // computes MAX internally in the wallet engine.
        val signed = wallet.buildSignedPayment(
            recipients = listOf(vaultAddressBase58 to vaultDuffs),
            network = toSdkNetwork(Constants.NETWORK_PARAMETERS),
            coreSignerHandle = manager.mnemonicResolverHandle,
            opReturnData = memo,
            preserveOutputOrder = true,
            changeToFirstInput = true
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
     * Spendable UTXO count, for sizing the MAX Maya deposit's fee reserve
     * ([mayaMaxFeeReserveDuffs]).
     *
     * SDK-only by construction: [CutoverUiSource.currentSpendableUtxoCount],
     * a COUNT over exactly the `txos` rows whose amounts the balance sums.
     * NOT dashj's `calculateAllSpendCandidates` — this branch deletes that leg.
     *
     * Falls back to [MAYA_MAX_RESERVE_MIN_INPUTS] when the count is
     * unavailable: over-reserving is lossless (the remainder returns as
     * change) whereas under-reserving refuses the deposit, so the fallback
     * errs high.
     */
    private val spendableUtxoCount: suspend (String) -> Int = { MAYA_MAX_RESERVE_MIN_INPUTS },
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
        source = DashSdkL1SendSource(sdkService),
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
        spendableUtxoCount = { walletIdHex ->
            // Same SDK source the cutover UI reads, constructed from the
            // DashSdkService this service already injects — so no new DI edge
            // and no cycle (CutoverUiDataService does not depend on this
            // service). Null means "unavailable", not "zero", so fall back
            // high rather than under-reserving.
            DashSdkCutoverUiSource(sdkService).currentSpendableUtxoCount(walletIdHex)
                ?: MAYA_MAX_RESERVE_MIN_INPUTS
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
     *   `SelectionStrategy::All` over the POOLED `ALL_SPENDABLE` funding
     *   set — BIP44 + BIP32 + every DashPay receival account in ONE
     *   transaction, one output worth `total − fee`, no change), with the
     *   iOS-validated max pattern: the first attempt floors the
     *   deliverable at [sendAllFloorDuffs]`(pooled spendable, size-based
     *   reserve)` ([sendAllFeeReserveDuffs] over the pooled UNSPENT output
     *   count); an engine-reported insufficient-at-fee
     *   ([isSendAllShortfall], provably pre-broadcast) is retried ONCE
     *   engine-authoritatively (floor 1, deliverable `total − fee`), with
     *   BOTH attempts under a single [SdkL1SendSource.withCoreSendLock]
     *   acquisition. The drain is REFUSED (NotBroadcast) while the held
     *   dashj wallet tracks any app-locked spendable output OR the seam
     *   registry holds any lock — see [hasAppLockedSpendableOutputs] and
     *   [SeamOutputLockRegistry].
     *   [amount] is display-typed for a send-all — the
     *   engine decides the deliverable — but must still be positive.
     * @param beforeBroadcast invoked after ALL preflights pass and before
     *   ANY broadcast — immediately before the single broadcast attempt.
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

        // Send-all guards and floor. Order matters:
        // 1. the app-locked-output guards (pre-everything, fail closed);
        // 2. beforeBroadcast() — the call site's pre-send conditions may
        //    throw (LeftoverBalanceException), and a throw must leave
        //    NOTHING broadcast;
        // 3. the reserve + floor reads. Every failure through step 3 is
        //    NotBroadcast by construction (the payment was never built),
        //    never Ambiguous.
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
        // and the dashj path surfaces the same throw the same way.
        beforeBroadcast()

        val sendAllFloor = if (emptyWallet) {
            // Send-all floor (iOS-validated max pattern): pooled spendable −
            // size-based fee reserve. The pooled ALL_SPENDABLE drain delivers
            // BIP44 + BIP32 + every DashPay receival account in ONE
            // transaction (v41int19, dashpay/platform#4329), so the old
            // sweep-each-receival-account-first pass is gone. The reserve is
            // sized from the pooled UNSPENT output count (the iOS
            // dashwallet-ios#928 model — a flat reserve under-reserved for
            // many-input wallets); a count-read failure falls back to the
            // flat constant, contained inside the source. Strictly
            // pre-broadcast, so a read failure stays NotBroadcast by
            // construction, never Ambiguous.
            val reserveDuffs = sendAllFeeReserveDuffs(
                try {
                    source.pooledSpendableUtxoCount(walletIdHex)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    // Advisory read — never fails the send (the production
                    // source already contains its failures to null; this
                    // catch covers any source that leaks the throw anyway).
                    log.warn("SDK {}: pooled UTXO count read failed; using the flat fee reserve", operation, t)
                    null
                }
            )
            try {
                sendAllFloorDuffs(source.spendableBalanceDuffs(walletIdHex), reserveDuffs)
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
     * The largest amount a MAYACHAIN deposit can pay a vault right now:
     * spendable balance MINUS a fee reserve ([mayaMaxFeeReserveDuffs]).
     *
     * The deposit built from this figure is an ORDINARY fixed-amount send, not
     * a drain: the app names the amount and the transaction pays exactly that,
     * so quote and payment are equal by construction — there is no
     * under-delivery gap for NEAR Intents to refuse. The reserve's unused
     * remainder comes back as change, which is what makes over-reserving
     * lossless. Same system as the shielded max-shield reserve and Buy
     * Credits; a MAX sell therefore leaves a small remnant rather than
     * emptying the wallet to zero, which is deliberate and not surfaced.
     *
     * ## Why not a drain
     *
     * A drain (`SelectionStrategy.ALL`) delivered `total − fee` with no change
     * — and therefore no wallet-owned output at all. Compact block filters
     * match wallet script pubkeys only, so that transaction is never matched
     * in a block, its context never reaches `CONTEXT_IN_BLOCK`, and the wallet
     * keeps counting the spent inputs as spendable (mainnet `a5c99aec…`,
     * `1f608a9a…`: balance inflated by the whole deposit, row stuck on
     * "Sending" forever). Change restores that output and the deposit settles
     * like any other send.
     *
     * Revisit when the SDK computes MAX internally in the wallet engine — the
     * engine should own the amount, not this arithmetic. Until then, do not
     * reintroduce `SelectionStrategy.ALL` here.
     *
     * [memoSizeBytes] defaults to the 80-byte OP_RETURN ceiling and sizes the
     * reserve's data carrier, so a shorter real memo only over-reserves
     * slightly — the safe direction.
     *
     * Returns 0 when the reserve exceeds the spendable balance (the caller
     * surfaces "not enough funds" rather than quoting a negative amount).
     * Throws like [buildDeferredMayaDeposit] on gate/bind failures.
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
        // FAIL-CLOSED (funds-critical): a max deposit selects (essentially)
        // every spendable UTXO, so coin selection reaches app-locked outputs
        // (CrowdNode) — which [spendableBalanceDuffs] deliberately INCLUDES and
        // the FFI cannot be told to exclude. Sweep-scale is what matters here,
        // not whether the build is technically a drain: withholding a fee
        // reserve leaves change but still spends the locked coins. Same guard
        // the send-all path applies, for the same reason: refuse to quote
        // rather than sweep protected funds into a swap. A partial (non-max)
        // deposit keeps the ordinary send's exposure.
        //
        // [buildDeferredMayaDeposit] enforces this too, for every max caller.
        // Do NOT delete this copy as redundant: quoting must refuse loudly
        // here, rather than let a later build failure read as "your maximum
        // is 0".
        check(!hasProtectedOutputs("l1MayaMaxDeposit")) {
            "wallet has app-locked outputs (CrowdNode); a max swap deposit would spend them"
        }
        val spendable = source.spendableBalanceDuffs(walletIdHex)
        val utxoCount = spendableUtxoCount(walletIdHex)
        val reserve = mayaMaxFeeReserveDuffs(utxoCount, memoSizeBytes)
        val max = (spendable - reserve).coerceAtLeast(0L)
        log.info(
            "SDK l1MayaMaxDeposit: max deposit {} duffs (spendable {}, reserve {}, {} utxos, {}-byte memo)",
            max, spendable, reserve, utxoCount, memoSizeBytes
        )
        return max
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
     * Under [isMaxDeposit] this refuses outright when the wallet holds
     * app-locked outputs (CrowdNode), the same fail-closed guard the send-all
     * path applies — see the check in the body for why it lives here rather
     * than at the call site. The flag marks SWEEP SCALE, not a drain: a max
     * deposit is an ordinary fixed-amount send of `spendable − reserve`
     * ([maxMayaDepositDuffs]), so it still names its amount and still leaves
     * change.
     */
    suspend fun buildDeferredMayaDeposit(
        vaultAddressBase58: String,
        vaultDuffs: Long,
        memo: ByteArray,
        isMaxDeposit: Boolean = false
    ): SdkDeferredPayment {
        // Every build names its own amount now, max included.
        check(vaultDuffs > 0) { "Maya vault amount must be positive, got $vaultDuffs" }
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
        // FAIL-CLOSED GUARD (funds-critical), max deposits only: a max deposit
        // selects (essentially) every spendable UTXO the pooled default reaches
        // — BIP44 + BIP32 + every DashPay contact-receiving account — and the
        // FFI has no exclusion API, so with any app-locked output present
        // (CrowdNode) it would sweep protected funds into a vault, irreversibly
        // once broadcast. Withholding a fee reserve leaves change but does NOT
        // narrow which coins are selected, so the guard applies exactly as it
        // did to the drain. It is WALLET-WIDE, not per-account, so it still
        // covers the sweep after the pooled default widened it. Enforced HERE,
        // in the primitive, rather than trusting the caller to have measured
        // first: [maxMayaDepositDuffs] does check, and [MayaBlockchainApiImpl]
        // does call it, but that is a call-site convention and a convention is
        // one refactor away from being skipped. A partial (non-max) deposit is
        // not guarded — it keeps the ordinary send's exposure, unchanged.
        //
        // [maxMayaDepositDuffs] keeps its own copy of this check deliberately,
        // so quoting refuses loudly instead of degrading to "your maximum is 0".
        if (isMaxDeposit) {
            check(!hasProtectedOutputs("l1DeferredMayaBuild")) {
                "wallet has app-locked outputs (CrowdNode); a max swap deposit would spend them"
            }
        }
        val payment = source.buildDeferredMayaDeposit(walletIdHex, vault, vaultDuffs, memo)
        log.info(
            "SDK l1DeferredMayaBuild: built {} ({} duffs to the vault{}, {}-byte memo, fee {} duffs), inputs reserved",
            payment.txidHex,
            vaultDuffs,
            if (isMaxDeposit) " (MAX, spendable − reserve)" else "",
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
