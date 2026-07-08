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
}
