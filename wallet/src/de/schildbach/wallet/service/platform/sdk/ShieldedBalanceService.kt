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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.dash.wallet.common.money.Dash

/**
 * Direction of one shielded activity entry, from the wallet's point of
 * view: [IN] funds arrived in the shielded pool, [OUT] funds left it,
 * [INTERNAL] a self-transfer (e.g. shielding the wallet's own credits
 * into its own pool).
 */
enum class ShieldedActivityDirection { IN, OUT, INTERNAL }

/**
 * One user-facing shielded activity entry, mapped from the SDK's Room
 * `shielded_activities` row into app-neutral types for the upcoming
 * shielded-balances UI. Deliberately small: the UI phase renders a
 * timeline of (direction, amount, time, optional memo).
 *
 * @param id stable row id — the SDK's `entryId` (sha256 of the entry's
 *   visible output commitments) as lowercase hex; usable as a list key.
 * @param direction see [ShieldedActivityDirection].
 * @param amount principal amount (fees excluded), converted from Platform
 *   credits to [Dash] (floor at 1e11 credits / DASH → 1e8 duffs / DASH).
 * @param timestampMs record time, Unix milliseconds.
 * @param memo decoded UTF-8 text memo, or null when the entry carries no
 *   (readable) memo. Never logged by the service.
 * @param pending true while the entry's state transition has not been
 *   confirmed by a shielded sync pass. Failed entries are not surfaced.
 */
data class ShieldedActivityEntry(
    val id: String,
    val direction: ShieldedActivityDirection,
    val amount: Dash,
    val timestampMs: Long,
    val memo: String?,
    val pending: Boolean
)

/**
 * Outcome payload of [ShieldedBalanceService.shieldFromWallet] on the
 * [SdkWriteResult.Broadcast] arm. The operation is TWO-staged Rust-side
 * (see the method KDoc): (a) an L1 asset-lock transaction is built from
 * the SDK wallet's Core UTXOs and broadcast, then (b) the Type 18
 * `ShieldFromAssetLock` transition consumes the lock into the shielded
 * pool. Stage (a) is the real L1 spend — once it happened the result is
 * `Broadcast` even if (b) still needs a retry.
 */
enum class ShieldFromWalletOutcome {
    /** Both stages done: lock broadcast AND the shield transition submitted. */
    COMPLETED,

    /**
     * The L1 asset lock was broadcast (funds left the spendable balance)
     * but the shield transition did not complete. The lock is tracked in
     * the SDK's persistence and [ShieldedBalanceService.resumePendingWalletShields]
     * retries stage (b) idempotently — the UI should tell the user the
     * transfer will finish automatically, and must NOT offer a manual
     * "send again".
     */
    SHIELD_PENDING_RETRY
}

/**
 * Readiness/sync state of the shielded runtime, for UI that must not show a
 * bare "0" while the pool is still catching up:
 *
 * - [NOT_READY]: the flag is off, or [ShieldedBalanceService.ensureShieldedReady]
 *   has not yet completed a successful bring-up pass. The balance flow only
 *   emits `Dash.ZERO` here — a placeholder, not a real balance.
 * - [SYNCING]: the runtime is ready but a shielded sync pass is in flight
 *   (or the first pass since bring-up has not finished). A funded wallet can
 *   read `Dash.ZERO` here for minutes while the pool re-scans, so the UI
 *   should show a "syncing" placeholder rather than the zero.
 * - [READY]: bring-up done AND at least one sync pass has finished with no
 *   pass currently in flight — the balance is trustworthy (and may
 *   legitimately be zero for an empty pool).
 */
enum class ShieldedSyncStatus { NOT_READY, SYNCING, READY }

/**
 * Phase 4 service layer (`docs/kotlin-sdk-migration-plan.md`): the wallet's
 * SHIELDED (Orchard) balance runtime on the Dash Platform Kotlin SDK,
 * behind the runtime flag [de.schildbach.wallet.ui.dashpay.utils
 * .DashPayConfig.USE_KOTLIN_SDK_SHIELDED] (default OFF). This is the API
 * surface the Figma-based shielded UI will sit on — it has NO production
 * call sites yet.
 *
 * ## Lifecycle ([ensureShieldedReady])
 *
 * One successful pass (mirroring the SDK example app's
 * `AppContainer.rebindWalletScopedServices`) establishes, in order:
 *
 * 1. the SDK runtime is up ([DashSdkService.ensureStarted]),
 * 2. the per-network Orchard commitment-tree SQLite file is open
 *    (`configureShielded`, `shielded_tree_<network>.sqlite` under the
 *    app's files dir),
 * 3. the app's bound SDK wallet ([SdkWalletBinder] must have bound it —
 *    this service NEVER prompts for the seed; the SDK's mnemonic resolver
 *    serves the ZIP-32 derivation from its Keystore-backed store) has its
 *    shielded sub-wallet registered for account 0 (`bindShielded`),
 * 4. the background shielded sync loop is running (`startShieldedSync`,
 *    the Rust default 60s interval — the example app does not override it).
 *
 * Idempotent (a success latches; later calls no-op), single-flight
 * (concurrent callers serialize), and INERT while the flag is off: no
 * native call, no SQLite file, no sync loop, `false` returned.
 *
 * ## Reads
 *
 * [observeShieldedBalance] / [observeShieldedActivity] are cold app-neutral
 * flows over the SDK's Room store; they emit `Dash.ZERO` / empty until a
 * successful [ensureShieldedReady] pass and then live-update as sync
 * passes land notes. Balance is the sum of unspent notes — the same source
 * the example app's wallet screen reads.
 *
 * ## Writes (the [SdkWriteResult] no-double-broadcast contract)
 *
 * [shieldFromCredits] (Type 15), [transferShielded] (16),
 * [unshieldToCredits] (17) and [withdrawToCore] (19) return:
 *
 * - [SdkWriteResult.NotBroadcast] whenever nothing was (or could have
 *   been) submitted — flag off, runtime not ready, invalid inputs, or a
 *   provably pre-broadcast SDK rejection (including the SDK-documented
 *   definitive non-execution codes `ShieldedBroadcastFailed` /
 *   `ShieldedNoRecordedAnchor`, whose note reservations Rust releases);
 * - [SdkWriteResult.Broadcast] on confirmed broadcast;
 * - [SdkWriteResult.Ambiguous] when the outcome cannot be proven
 *   pre-broadcast. `ShieldedSpendUnconfirmed` is the load-bearing case:
 *   the SDK documents it as "broadcast accepted, execution unconfirmed,
 *   do NOT retry" — the spent notes stay reserved Rust-side and the next
 *   shielded sync pass reconciles the outcome. Callers must surface an
 *   Ambiguous result as a terminal "may have gone through, do not retry"
 *   state, never as a retryable error.
 *
 * **Proving time:** every spend blocks for a Halo 2 proof (~30s on-device;
 * first-ever proof also builds the proving key). The SDK exposes no
 * per-proof progress callback (`ShieldedProver` offers only `warmUp()` /
 * `isReady()`, and [ensureShieldedReady] already kicks the warm-up), so
 * UI callers MUST show indeterminate progress for the duration of the
 * call and keep the operation off the main thread.
 *
 * Nothing amount-, address- or memo-derived is ever logged by this
 * service.
 */
interface ShieldedBalanceService {

    /**
     * Bring the shielded runtime up (see class KDoc for the pass).
     * Never throws (except cancellation); returns true when the runtime
     * is ready, false when the flag is off, shielded support is absent
     * from the native build, the app wallet is not bound to the SDK yet,
     * or the pass failed (retryable — call again on the next trigger).
     */
    suspend fun ensureShieldedReady(): Boolean

    /**
     * Stop the background shielded sync loop and drop the ready latch.
     * Safe to call when not ready. The commitment-tree store stays on
     * disk; the next [ensureShieldedReady] re-binds and resumes.
     */
    suspend fun stop()

    /**
     * Best-effort immediate sync pass — for screen-entry refreshes, so a
     * balance surface shows fresh notes on arrival instead of on the next
     * ~60s background tick (Brian: the More card should refresh as soon
     * as the screen becomes active). No-op when the runtime isn't ready;
     * failures are logged and swallowed (the background loop remains the
     * source of truth).
     */
    suspend fun syncNow()

    /**
     * Live unspent shielded balance, credits summed from the SDK's note
     * store and floored to [Dash]. Emits [Dash.ZERO] until
     * [ensureShieldedReady] succeeds.
     */
    fun observeShieldedBalance(): Flow<Dash>

    /**
     * Live shielded activity timeline, newest first. Emits an empty list
     * until [ensureShieldedReady] succeeds. Failed entries and entries
     * with unrecognized directions are not surfaced.
     */
    fun observeShieldedActivity(): Flow<List<ShieldedActivityEntry>>

    /**
     * Live [ShieldedSyncStatus] for the shielded runtime. Starts at
     * [ShieldedSyncStatus.NOT_READY] and stays there while the flag is off
     * (inert). After a successful [ensureShieldedReady] it reflects whether a
     * sync pass is in flight, flipping to [ShieldedSyncStatus.READY] once the
     * first pass has finished. UI reading [observeShieldedBalance] should
     * treat any non-[ShieldedSyncStatus.READY] value as "balance not yet
     * trustworthy" and show a syncing placeholder instead of the zero.
     */
    val shieldedSyncStatus: StateFlow<ShieldedSyncStatus>

    /**
     * The wallet's default shielded (Orchard) receive address for ZIP-32
     * account 0, bech32m-encoded for display (`dash1…` / `tdash1…`), or
     * null when the flag is off, the runtime is not ready, or the wallet
     * has no bound shielded sub-wallet. Never throws.
     */
    suspend fun shieldedReceiveAddress(): String?

    /**
     * Shield [amount] from the wallet's own Platform credit balance into
     * its own shielded pool (Type 15). Self-shield only — Rust always
     * targets this wallet's default Orchard address. Blocks for the ~30s
     * proof; the note arrives on the next shielded sync pass.
     */
    suspend fun shieldFromCredits(amount: Dash): SdkWriteResult<Unit>

    /**
     * Shield [amount] of the wallet's L1 (Core) balance into its own
     * shielded pool via a fresh asset lock + Type 18 `ShieldFromAssetLock`
     * — the "Dash Wallet → Shielded" direction. Self-shield only (the
     * recipient is the wallet's own default Orchard address).
     *
     * ## Architecture (decided from SDK sources — see the impl KDoc)
     *
     * The Kotlin SDK's `shieldedFundFromAssetLock` accepts NO externally
     * built transaction: the Rust side builds the asset lock from the SDK
     * wallet's OWN Core UTXOs and broadcasts it over the SDK's OWN SPV
     * peers. A dashj-built lock cannot be handed over. This op therefore
     * runs entirely on the SDK stack and is hard-gated on runtime
     * evidence that the SDK's L1 view matches dashj's:
     * [DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW] on, the shadow SPV
     * SYNCED, and the latest [L1ShadowSyncService] parity probe a fresh
     * full MATCH. Any gate failure returns
     * [SdkWriteResult.NotBroadcast] BEFORE anything is spent.
     *
     * ## Funds safety / the staged contract
     *
     * - [SdkWriteResult.NotBroadcast] — provably nothing spent (flag off,
     *   gate refused, fee-floor preflight, or a definitive pre-broadcast
     *   SDK rejection with no tracked lock recorded).
     * - [SdkWriteResult.Broadcast] with [ShieldFromWalletOutcome.COMPLETED]
     *   — full pipeline done; the note arrives on the next shielded sync.
     * - [SdkWriteResult.Broadcast] with
     *   [ShieldFromWalletOutcome.SHIELD_PENDING_RETRY] — the L1 lock is
     *   out (evidenced by a new tracked-asset-lock row in the SDK's
     *   store) but the shield transition failed; it is retried
     *   idempotently by [resumePendingWalletShields].
     * - [SdkWriteResult.Ambiguous] — the call failed AND the tracked-lock
     *   evidence could not be read; the retry sweep still recovers any
     *   lock once the SDK's persistence catches up.
     *
     * The recipient receives `amount − pool fee` (the flat shielded fee
     * plus the protocol's asset-lock base cost come out of the locked
     * amount). Blocks for the ~30s Halo 2 proof.
     */
    suspend fun shieldFromWallet(amount: Dash): SdkWriteResult<ShieldFromWalletOutcome>

    /**
     * Whether [shieldFromWallet]'s L1 funding gate would currently pass
     * (flag on + shadow SPV parity MATCH). Cheap local read for UI
     * gating; the write path re-checks. Never throws.
     *
     * One-shot snapshot — use it only for the pre-broadcast preflight. UI
     * that stays open while the gate can flip (the shadow harness reaching
     * SYNCED with a fresh parity MATCH) MUST observe
     * [observeWalletShieldingAvailable] instead, or the blocked-state UI
     * never re-renders when the gate opens.
     */
    suspend fun isWalletShieldingAvailable(): Boolean

    /**
     * Live version of [isWalletShieldingAvailable] for open screens: emits
     * whether the L1 funding gate is currently open, re-deriving it from
     * the same [evaluateWalletFundingGate] logic on every
     * [L1ShadowSyncService.latestParity] emission (the probe re-emits a
     * fresh report every ~60s while the shadow runs, so a gate that opens
     * after the harness reaches SYNCED propagates without a screen
     * re-entry). [kotlinx.coroutines.flow.distinctUntilChanged]; conservative
     * `false` while the flag is off (inert — the parity flow stays null) or
     * before the first probe. Never throws.
     */
    fun observeWalletShieldingAvailable(): Flow<Boolean>

    /**
     * Retry stage (b) of any interrupted [shieldFromWallet]: resume every
     * shielded-top-up asset lock (`fundingTypeRaw == 5`) still tracked
     * un-consumed in the SDK's persistence via the SDK's
     * resume-by-outpoint FFI. Idempotent — the Rust side re-derives the
     * same shield amount from the on-chain lock value, rebroadcasts of a
     * `Built` lock reuse the identical txid, and a consumed lock simply
     * fails that row. Runs automatically after a successful
     * [ensureShieldedReady] pass; safe to call any time.
     *
     * @return the number of locks successfully resumed (0 when the flag
     *   is off, the runtime is not ready, or nothing was pending).
     */
    suspend fun resumePendingWalletShields(): Int

    /**
     * Shielded → shielded transfer (Type 16) to [toAddress] (a bech32m
     * Orchard address; malformed / wrong-network input is rejected as
     * [SdkWriteResult.NotBroadcast]). [memo] is an optional UTF-8 text
     * memo of at most 32 bytes. Blocks for the ~30s proof.
     */
    suspend fun transferShielded(toAddress: String, amount: Dash, memo: String?): SdkWriteResult<Unit>

    /**
     * Unshield [amount] back to the wallet's OWN Platform credit balance
     * (Type 17) — the target is the wallet's lowest-index unused DIP-17
     * Platform receive address from the SDK's address store; no address
     * available yet is a [SdkWriteResult.NotBroadcast]. Blocks for the
     * ~30s proof.
     */
    suspend fun unshieldToCredits(amount: Dash): SdkWriteResult<Unit>

    /**
     * Withdraw [amount] from the shielded pool to the Core L1 address
     * [addressBase58] (Type 19). The network converts credits → duffs at
     * the fixed 1000:1 rate; the L1 fee rate is pinned to 1 duff/byte
     * (the only DPP-accepted Fibonacci rate the example app uses). Rust
     * re-validates the address against the wallet's network. Blocks for
     * the ~30s proof.
     */
    suspend fun withdrawToCore(addressBase58: String, amount: Dash): SdkWriteResult<Unit>
}
