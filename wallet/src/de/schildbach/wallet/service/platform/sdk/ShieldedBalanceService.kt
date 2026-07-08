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
