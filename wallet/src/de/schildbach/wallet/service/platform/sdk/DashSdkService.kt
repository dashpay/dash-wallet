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

import org.dashfoundation.dashsdk.Sdk
import org.dashfoundation.dashsdk.wallet.PlatformWalletManager

/**
 * Lifecycle owner for the Dash Platform Kotlin SDK inside the wallet app —
 * the Phase 3 bootstrap seam of the dashj → Kotlin SDK migration
 * (see `docs/kotlin-sdk-migration-plan.md`, "Phase 3 — Introduce the SDK").
 *
 * ## Phased plan
 *
 * - **Phase 3 (this scaffold):** the SDK can be brought up on demand —
 *   native init, Room database, Keystore-backed secret storage, per-network
 *   SDK build, wallet-manager activation — but NOTHING starts unless
 *   [ensureStarted] is explicitly called. No production code path calls it
 *   yet, so default app behavior is unchanged.
 * - **Phase 3b:** bridge the app's PIN-encrypted dashj BIP39 seed into the
 *   SDK's `WalletStorage` (via [PlatformMnemonicProvider]), port the
 *   `service/platform` stack (`PlatformService`, `PlatformSyncService`,
 *   `PlatformBroadcastService`, repositories) onto the SDK query
 *   namespaces, and wire the DashPay sync loops.
 * - **Phase 4+:** shielded balances, then the L1 cutover.
 *
 * ## Lifecycle rules (mirrors the SDK example app's `AppContainer`)
 *
 * - The SDK instance and its [PlatformWalletManager] are **network-locked**
 *   at creation. Switching networks means closing and rebuilding both —
 *   there is no reconfiguration path. In this app the network is fixed per
 *   build flavor ([de.schildbach.wallet.Constants.NETWORK_PARAMETERS]), so
 *   a rebuild only ever happens through [stop] + [ensureStarted].
 * - [ensureStarted] is idempotent and safe to call from multiple
 *   coroutines; the first caller pays the bootstrap cost.
 */
interface DashSdkService {

    /** True once [ensureStarted] has completed and [stop] has not run. */
    val isStarted: Boolean

    /**
     * Bring the SDK up if it is not already running. Performs, once, in
     * order (faithful to the example app's `AppContainer.bootstrap()` /
     * `activateManager()`):
     *
     * 1. `Sdk.initialize()` — native library load + `dash_sdk_init`,
     * 2. SDK logging setup,
     * 3. Room [org.dashfoundation.dashsdk.persistence.DashDatabase] creation,
     * 4. [org.dashfoundation.dashsdk.security.WalletStorage] init,
     * 5. per-network `Sdk.create(...)` for the network mapped from
     *    `Constants.NETWORK_PARAMETERS`,
     * 6. [org.dashfoundation.dashsdk.wallet.WalletManagerStore] activation,
     * 7. `loadPersistedWallets()`.
     *
     * Sync-service binding (platform-address / shielded / DashPay loops)
     * is deliberately NOT performed — that lands in Phase 3b/4.
     *
     * Throws if bootstrap fails; the service stays stopped and a retry is
     * permitted.
     */
    suspend fun ensureStarted()

    /**
     * Tear down: close all wallet managers, the SDK handle and the Room
     * database. The native library itself stays loaded (process-wide).
     * Safe to call when not started.
     */
    suspend fun stop()

    /** The live SDK handle, or null if [ensureStarted] has not completed. */
    fun sdkOrNull(): Sdk?

    /**
     * The activated wallet manager for the app's network, or null if
     * [ensureStarted] has not completed.
     */
    fun walletManagerOrNull(): PlatformWalletManager?

    /**
     * Proof-of-life read-only query: resolve a DPNS username to its record
     * (JSON), or null if unregistered. Internally calls [ensureStarted].
     *
     * NOT called from any production code path — it exists so Phase 3b has
     * a verified end-to-end entry point (JNI → Rust → DAPI) to build on.
     */
    suspend fun resolveUsername(name: String): String?

    /**
     * Bind the app's dashj wallet to the SDK: derive an SDK wallet from the
     * same BIP39 phrase so both stacks operate on one seed — the Phase 3b
     * bridge (`docs/kotlin-sdk-migration-plan.md`). Internally calls
     * [ensureStarted]. NOT called from any production code path yet; the
     * Phase 3c `service/platform` port becomes the first caller.
     *
     * ## Contract
     *
     * - **Input**: [seedWords] straight from
     *   [PlatformMnemonicProvider.getMnemonicWords] — i.e. the caller has
     *   already authenticated the user and decrypted the dashj seed. Words
     *   are never logged or persisted app-side.
     * - **Idempotent**: the SDK's `createWallet` does NOT dedup (re-running
     *   it re-registers the same derived wallet id), so this call first
     *   matches the phrase against the mnemonics already persisted in the
     *   SDK's Keystore-backed `WalletStorage` for the wallets
     *   `loadPersistedWallets()` restored; on a match it returns the
     *   existing id without touching native wallet creation.
     * - **Resolver wiring**: on first bind, `createWallet` persists the
     *   phrase into the SDK's `WalletStorage` keyed by the derived wallet
     *   id (Kotlin-side, after the FFI returns the id — verified against
     *   `PlatformWalletManager.createWallet`); the manager's
     *   `MnemonicResolverAndPersister` reads from that same storage, so
     *   every post-bind derivation resolves WITHOUT re-prompting the user.
     * - **Birth height**: the SDK wants a block *height* to start compact-
     *   filter scanning from, but the app only knows a birth *time*
     *   ([org.bitcoinj.wallet.Wallet.getEarliestKeyCreationTime]), and a
     *   too-high height silently skips funds while `0` (genesis) is merely
     *   slower. Phase 3b therefore always requests a full scan; the
     *   time→height mapping (via headers/checkpoints) lands with the
     *   Phase 5 migration flow. [birthTimeSecs] is accepted now so call
     *   sites don't change shape then.
     *
     * @param seedWords the wallet's BIP39 words, already decrypted.
     * @param birthTimeSecs the dashj wallet's earliest-key time (Unix
     *   seconds), or null if unknown. Recorded in the signature for
     *   Phase 5; does not affect Phase 3b behavior.
     * @return the bound SDK wallet id as lowercase hex (64 chars).
     */
    suspend fun bindAppWallet(seedWords: List<String>, birthTimeSecs: Long?): String

    /**
     * True when [identityId] (32 bytes) is a *managed* identity of the SDK
     * wallet [walletIdHex] — i.e. the Rust `IdentityManager` holds its slot
     * and can derive/sign with its keys (the precondition every
     * [SdkDashPayWrites] preflight probes). Local snapshot read
     * (`dashpay.syncState`), no network. Internally calls [ensureStarted].
     * Returns false when the wallet id is not loaded.
     */
    suspend fun isIdentityManaged(walletIdHex: String, identityId: ByteArray): Boolean

    /**
     * Phase 3f identity discovery: scan the bound SDK wallet's DIP-9
     * identity-authentication tree (`m/9'/coin'/5'/0'/0'/identity_index'`)
     * and ATTACH every identity registered on Platform for one of those
     * keys to the wallet's Rust `IdentityManager` (persisted via Room), so
     * [isIdentityManaged] turns true and the SDK can derive its keys.
     *
     * This is the op that adopts the app's EXISTING dashj-registered
     * identity (registered at identity index 0 — see the [SdkDashPayWrites]
     * key-derivation parity note) into the SDK wallet: the FFI
     * (`platform_wallet_discover_identities`) derives consecutive MASTER
     * keys, queries Platform by unique pubkey hash, and stops after the
     * gap limit. Network I/O — one Platform query per probed slot.
     *
     * @param walletIdHex the bound wallet ([bindAppWallet]'s return).
     * @param startIndex first identity index to probe; 0 forces a full
     *   rescan (the app identity lives at index 0), negative resumes from
     *   the wallet's cached scan cursor.
     * @return the NEWLY-discovered 32-byte identity ids (already-managed
     *   identities are not re-reported).
     * @throws Exception if the wallet is not loaded or the scan fails.
     */
    suspend fun discoverIdentities(walletIdHex: String, startIndex: Int = 0): List<ByteArray>
}
