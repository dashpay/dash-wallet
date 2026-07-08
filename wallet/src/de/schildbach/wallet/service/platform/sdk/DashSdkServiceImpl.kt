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

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import de.schildbach.wallet.Constants
import de.schildbach.wallet_test.BuildConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.params.DevNetParams
import org.dashfoundation.dashsdk.Network
import org.dashfoundation.dashsdk.Sdk
import org.dashfoundation.dashsdk.config.SdkConfig
import org.dashfoundation.dashsdk.persistence.DashDatabase
import org.dashfoundation.dashsdk.security.WalletStorage
import org.dashfoundation.dashsdk.wallet.PlatformWalletManager
import org.dashfoundation.dashsdk.wallet.WalletManagerStore
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Map the app's flavor-selected dashj network onto the SDK's [Network].
 *
 * The wallet's network is fixed at build time
 * ([de.schildbach.wallet.Constants.NETWORK_PARAMETERS]: prod = mainnet,
 * `_testNet3`/staging = testnet, devnet flavor = devnet), so unlike the SDK
 * example app there is no runtime network switching — but the SDK's
 * rebuild-not-reconfigure rule still applies if that ever changes.
 *
 * Kept as a pure function (no native, no Android) so it is unit-testable
 * on the host JVM.
 */
internal fun toSdkNetwork(parameters: NetworkParameters): Network = when {
    parameters.id == NetworkParameters.ID_MAINNET -> Network.MAINNET
    parameters.id == NetworkParameters.ID_TESTNET -> Network.TESTNET
    parameters is DevNetParams -> Network.DEVNET
    parameters.id == NetworkParameters.ID_REGTEST -> Network.REGTEST
    else -> throw IllegalArgumentException("No SDK network mapping for ${parameters.id}")
}

/**
 * Default [DashSdkService] implementation — the Phase 3 bootstrap scaffold
 * (`docs/kotlin-sdk-migration-plan.md`).
 *
 * ## Laziness contract (load-bearing)
 *
 * Construction touches NOTHING: no native library, no Room, no Keystore,
 * no `Constants`. Every SDK object lives inside the [SdkRuntime] built by
 * [ensureStarted]; until that is explicitly called this singleton is an
 * inert holder, so Hilt can instantiate it (even eagerly) without changing
 * app behavior or loading `libdash_sdk`. This is verified by a plain-JVM
 * unit test (`DashSdkServiceImplTest`) where constructing the class must
 * not throw `UnsatisfiedLinkError`.
 *
 * The SDK's own classes are also safe to *reference* statically: the only
 * `System.loadLibrary` call sits inside `NativeLoader.ensureLoaded()`,
 * which runs first via `Sdk.initialize()` in [bootstrap] — never from a
 * static initializer.
 *
 * ## Bootstrap order
 *
 * [bootstrap] follows the SDK example app's `AppContainer` faithfully:
 * the container-construction step (database / walletStorage /
 * walletManagerStore) followed by `bootstrap()` (`Sdk.initialize` →
 * logging → per-network `Sdk.create`) and `activateManager()`
 * (`WalletManagerStore.activate` → `loadPersistedWallets`). The example's
 * remaining steps are intentionally deferred:
 *
 * - `loadKnownContractsIntoSdk` — no contracts are persisted yet (3b),
 * - sync-service binding (`platformBalanceSyncService.configure`,
 *   `startPlatformAddressSync` / `startShieldedSync` /
 *   `startDashPaySync`) — Phase 3b/4,
 * - network-switch observer — the app's network is flavor-fixed.
 *
 * @param mnemonicProvider Phase 3b seam for bridging the PIN-encrypted
 *   dashj seed into the SDK; unused in Phase 3 (the placeholder binding
 *   throws), held here so the wallet-binding step lands as a pure
 *   implementation swap.
 */
@Singleton
class DashSdkServiceImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @Suppress("unused") private val mnemonicProvider: PlatformMnemonicProvider
) : DashSdkService {

    /** Everything with a native or persistence footprint lives here. */
    private class SdkRuntime(
        val database: DashDatabase,
        val walletStorage: WalletStorage,
        val walletManagerStore: WalletManagerStore,
        val sdk: Sdk,
        val walletManager: PlatformWalletManager
    )

    private val lock = Mutex()

    @Volatile
    private var runtime: SdkRuntime? = null

    override val isStarted: Boolean
        get() = runtime != null

    override fun sdkOrNull(): Sdk? = runtime?.sdk

    override fun walletManagerOrNull(): PlatformWalletManager? = runtime?.walletManager

    override suspend fun ensureStarted() {
        lock.withLock {
            if (runtime == null) {
                runtime = bootstrap()
            }
        }
    }

    override suspend fun stop() {
        lock.withLock {
            val current = runtime ?: return
            runtime = null
            log.info("stopping Dash Platform SDK")
            // Managers first (each closes its native bundle + resolver/signer
            // children), then the SDK handle they were built against, then
            // the database — reverse of the bootstrap order.
            runCatching { current.walletManagerStore.closeAll() }
                .onFailure { log.warn("failed to close wallet managers", it) }
            runCatching { current.sdk.close() }
                .onFailure { log.warn("failed to close SDK handle", it) }
            runCatching { current.database.close() }
                .onFailure { log.warn("failed to close SDK database", it) }
        }
    }

    override suspend fun resolveUsername(name: String): String? {
        ensureStarted()
        val sdk = checkNotNull(runtime) { "SDK runtime missing after ensureStarted()" }.sdk
        return sdk.dpns.resolve(name)
    }

    /**
     * One-shot bring-up; caller holds [lock]. On any failure every
     * partially-created resource is torn down and the exception rethrown,
     * leaving the service stopped (retryable).
     */
    private suspend fun bootstrap(): SdkRuntime {
        val network = toSdkNetwork(Constants.NETWORK_PARAMETERS)
        log.info(
            "bootstrapping Dash Platform SDK for network={} (flavor={})",
            network.networkName, BuildConfig.FLAVOR
        )

        // 1. One-time native library load + dash_sdk_init, then logging —
        //    ← AppContainer.bootstrap() steps 1–2. Idempotent.
        Sdk.initialize()
        Sdk.enableLogging(if (BuildConfig.DEBUG) Sdk.LogLevel.DEBUG else Sdk.LogLevel.WARN)

        // 2. Storage layer — ← AppContainer construction (database,
        //    walletStorage, walletManagerStore fields). No BiometricGate:
        //    the app gates secrets with its own PIN (SecurityGuard), and
        //    the auth-gated key flows are a Phase 3b concern.
        var database: DashDatabase? = null
        var sdk: Sdk? = null
        try {
            database = DashDatabase.create(context)
            val walletStorage = WalletStorage(context)
            val walletManagerStore = WalletManagerStore(database, walletStorage)

            // 3. Per-network SDK build — ← AppState.initializeSdk called from
            //    bootstrap(). Mainnet/testnet need no overrides; devnet would
            //    need a quorum URL (unsupported by this scaffold).
            sdk = Sdk.create(SdkConfig(network = network))

            // 4. Activate the network-locked manager, then restore any
            //    persisted SDK wallets — ← AppContainer.activateManager()
            //    (activate + loadPersistedWallets). None exist in Phase 3,
            //    so this is a fast no-op restore; sync-service binding is
            //    deliberately omitted until Phase 3b/4.
            val walletManager = walletManagerStore.activate(network, sdk)
            val restored = walletManager.loadPersistedWallets()
            log.info(
                "Dash Platform SDK started: version={}, restored {} wallet(s)",
                Sdk.version(), restored.size
            )

            return SdkRuntime(database, walletStorage, walletManagerStore, sdk, walletManager)
        } catch (t: Throwable) {
            log.error("Dash Platform SDK bootstrap failed", t)
            runCatching { sdk?.close() }
            runCatching { database?.close() }
            throw t
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(DashSdkServiceImpl::class.java)
    }
}
