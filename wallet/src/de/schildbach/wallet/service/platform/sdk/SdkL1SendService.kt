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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Address
import org.dash.wallet.common.money.Dash
import org.dashfoundation.dashsdk.errors.DashSdkError
import org.dashfoundation.dashsdk.errors.mapNativeErrors
import org.dashfoundation.dashsdk.wallet.ManagedPlatformWallet
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
     * Run [block] while holding the SDK wallet's per-wallet core-send
     * lock — the same mutex `ManagedPlatformWallet.sendToAddresses`
     * serializes its builds under. [SdkL1SendService] wraps the send-all
     * attempt AND its single adjust-down retry in ONE acquisition, so a
     * concurrent plain send cannot interleave between the two attempts and
     * change the drained balance. The mutex is NOT reentrant: [block] must
     * not call [sendToAddress] (which re-acquires it inside the SDK) or
     * nest another [withCoreSendLock] — the only permitted call inside is
     * [sendAllToAddress], which is lock-free by contract. Default: runs
     * [block] directly (sources without a native mutex).
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
}

/** Production [SdkL1SendSource]: boots the SDK on demand. */
internal class DashSdkL1SendSource(
    private val service: DashSdkService
) : SdkL1SendSource {

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
        return wallet.sendToAddresses(
            recipients = listOf(addressBase58 to amountDuffs),
            network = toSdkNetwork(Constants.NETWORK_PARAMETERS),
            coreSignerHandle = manager.mnemonicResolverHandle
        )
    }

    override suspend fun spendableBalanceDuffs(walletIdHex: String): Long {
        val manager = manager()
        val wallet = checkNotNull(manager.wallets.value[walletIdHex]) { "SDK wallet not loaded" }
        val balance = wallet.balance()
        return balance.confirmed + balance.unconfirmed
    }

    override suspend fun <T> withCoreSendLock(walletIdHex: String, block: suspend () -> T): T {
        val manager = manager()
        val wallet = checkNotNull(manager.wallets.value[walletIdHex]) { "SDK wallet not loaded" }
        // The same per-wallet mutex ManagedPlatformWallet.sendToAddresses
        // serializes its builds under — the whole reason the split builder
        // API is `internal`. Resolved BEFORE any native call, so a lookup
        // failure is provably pre-broadcast. Held ONCE across the whole
        // [block] (drain attempt + adjust-down retry) so a concurrent plain
        // send cannot interleave between the attempts. No deadlock: the
        // mutex is non-reentrant but nothing inside [block] re-acquires it —
        // sendAllToAddress below is lock-free by contract, and the plain
        // send path (sendToAddresses) is never invoked inside the block, so
        // every acquisition of this mutex is strictly sequential.
        val coreSendMutex = coreSendMutexOf(wallet)
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

    /**
     * The wallet's `coreSendMutex` via its public-static synthetic JVM
     * accessor (`access$getCoreSendMutex$p` — javac/kotlinc can't reference
     * synthetic members in source, reflection can; stable in the pinned
     * AAR). Failure throws [DashSdkError.InvalidState] so
     * [classifyCoreSendFailure] proves it pre-broadcast.
     */
    private fun coreSendMutexOf(wallet: ManagedPlatformWallet): Mutex = try {
        val accessor = ManagedPlatformWallet::class.java
            .getMethod("access\$getCoreSendMutex\$p", ManagedPlatformWallet::class.java)
        accessor.invoke(null, wallet) as Mutex
    } catch (t: Throwable) {
        throw DashSdkError.InvalidState(
            "send-all preflight: coreSendMutex unavailable via the pinned SDK binary", t
        )
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
 * latest [L1ShadowSyncService] parity probe: shadow SPV SYNCED and a
 * fresh (≤ [ShieldedBalanceServiceImpl.PARITY_MAX_AGE_MS]) report with
 * BOTH balance variants matching dashj. The helper is reused, not
 * duplicated. Spendable-balance semantics deliberately match dashj: there
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
     * Latest L1 shadow-sync parity probe — the funding-gate evidence,
     * same wiring as [ShieldedBalanceServiceImpl]. The default keeps the
     * gate CLOSED (funds-safe) for constructions that don't provide it.
     */
    private val l1Parity: () -> ParityReport? = { null },
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
    private val bridgeAfterBroadcast: (String) -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis
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
        l1Parity = { l1ShadowSyncService.latestParity.value },
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
     * over the latest parity report: shadow SPV SYNCED + fresh parity on
     * BOTH balance variants). Extracted so the debug settings screen can
     * show the live gate state without approximating the rule. Never
     * mutates anything and never throws — a parity-read failure reads as
     * a closed gate, exactly as it would on a real send ([safeParity]).
     * NOTE: a real send has additional preflights (flag, address, wallet
     * binding), so an open gate means "the SDK engine WOULD be used if
     * those pass", not a guarantee.
     */
    internal fun probeSendGate(): WalletFundingGate = evaluateWalletFundingGate(
        safeParity(), nowMs(), ShieldedBalanceServiceImpl.PARITY_MAX_AGE_MS
    )

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
     *   [amount] is display-typed for a send-all — the
     *   engine decides the deliverable — but must still be positive.
     * @param beforeBroadcast invoked after ALL preflights pass and
     *   immediately before the single broadcast attempt — the call site's
     *   dashj-equivalent pre-send conditions (leftover-balance check,
     *   which may throw `LeftoverBalanceException`). A throw here
     *   propagates unclassified, exactly as it would on the dashj path;
     *   nothing has been broadcast yet.
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
        // helper: shadow SYNCED + fresh parity on BOTH balance variants.
        // Shared with the debug settings status line via probeSendGate.
        val gate = probeSendGate()
        if (!gate.allowed) {
            return notBroadcast(operation, "L1 funding gate closed: ${gate.reason}", null)
        }

        // Send-all floor (iOS-validated max pattern): spendable − reserve,
        // read in PREFLIGHT so a balance-read failure is NotBroadcast by
        // construction, never Ambiguous.
        val sendAllFloor = if (emptyWallet) {
            // FAIL-CLOSED GUARD (funds-critical): the drain selects EVERY
            // spendable UTXO and the FFI has no exclusion API (see the
            // [hasAppLockedSpendableOutputs] KDoc) — with any app-locked
            // output present (CrowdNode) it would spend protected funds.
            // Checked BEFORE any build/balance call; a check failure also
            // blocks. Real fix: an upstream SDK UTXO lock/exclusion API
            // (iOS's add_inputs_from_outpoints binding is the porting
            // candidate).
            val hasLockedOutputs = try {
                hasAppLockedSpendableOutputs()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log.warn("SDK {}: app-locked-output preflight failed; blocking the drain (fail closed)", operation, t)
                true
            }
            // B7 union: seam-registered locks (SDK-only txs — CrowdNode
            // API-response outputs locked via WalletDataAdapter →
            // [SeamOutputLockRegistry]) are invisible to the dashj wallet
            // check above; OR them in so the drain cannot spend them
            // either. Fail closed: a registry read failure also blocks.
            val hasSeamLockedOutputs = try {
                seamOutputLockRegistry.hasAnyLocks()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log.warn("SDK {}: seam output-lock registry read failed; blocking the drain (fail closed)", operation, t)
                true
            }
            if (hasLockedOutputs || hasSeamLockedOutputs) {
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
            try {
                sendAllFloorDuffs(source.spendableBalanceDuffs(walletIdHex))
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                return notBroadcast(operation, "SDK spendable-balance read failed", t)
            }
        } else {
            null
        }

        // Call-site pre-send conditions (may throw, e.g. LeftoverBalanceException) —
        // deliberately outside the classification try: nothing broadcast yet and
        // the dashj path surfaces the same throw the same way.
        beforeBroadcast()

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

    /** [isValidAddress] with failures contained (a throw must not escape a preflight). */
    private fun addressValidSafe(address: String): Boolean = try {
        isValidAddress(address)
    } catch (e: Exception) {
        false
    }

    private fun safeParity(): ParityReport? = try {
        l1Parity()
    } catch (e: Exception) {
        log.warn("failed to read the L1 parity report; send gate stays closed", e)
        null
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
    }
}
