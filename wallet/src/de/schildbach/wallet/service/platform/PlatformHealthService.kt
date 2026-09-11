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
package de.schildbach.wallet.service.platform

import de.schildbach.wallet.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bitcoinj.core.NetworkParameters
import org.dash.platform.dapi.v0.CoreOuterClass
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dashj.platform.dapiclient.provider.DAPIGrpcMasternode
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coarse platform-network health, probed before asset-lock-funded
 * Platform operations (username creation). Purely ADVISORY — a DEGRADED
 * result warns the user that the operation may take longer than usual;
 * it must never block or disable anything (live incident: a freshly
 * confirmed funding tx sat ~10 minutes in dashj's identity-registration
 * retry loop because Platform's consensus core height lagged the L1 tip,
 * and the user saw a generic "processing" tile the whole time).
 */
enum class PlatformHealth {
    /** The platform side sees (roughly) the same chain we do. */
    NORMAL,

    /**
     * The DAPI node's core chain height trails our local dashj tip by
     * [PLATFORM_CORE_LAG_THRESHOLD_BLOCKS] or more — asset-lock proofs
     * built on recent blocks are likely to be rejected with
     * "core chain height ... is higher than the current consensus core
     * height" until it catches up.
     */
    DEGRADED,

    /** The probe failed or evidence is missing — say nothing. */
    UNKNOWN
}

/**
 * How far the platform-side core height may trail the local dashj tip
 * before the network counts as [PlatformHealth.DEGRADED]. One block of
 * skew is normal propagation/timing jitter (mirrors
 * [de.schildbach.wallet.service.platform.sdk.DASHJ_TIP_TOLERANCE_BLOCKS]).
 */
internal const val PLATFORM_CORE_LAG_THRESHOLD_BLOCKS = 2

/**
 * Pure decision function (host-testable): compares a platform-side core
 * chain height against the local dashj tip.
 *
 * Conservative on missing evidence: an unknown platform height or an
 * unusable local height (unsynced wallet / no state yet) yields
 * [PlatformHealth.UNKNOWN], never a warning.
 */
internal fun assessPlatformHealth(
    platformCoreHeight: Long?,
    localChainHeight: Long,
    lagThresholdBlocks: Int = PLATFORM_CORE_LAG_THRESHOLD_BLOCKS
): PlatformHealth = when {
    platformCoreHeight == null || platformCoreHeight <= 0 || localChainHeight <= 0 ->
        PlatformHealth.UNKNOWN
    platformCoreHeight <= localChainHeight - lagThresholdBlocks -> PlatformHealth.DEGRADED
    else -> PlatformHealth.NORMAL
}

/**
 * Probes one DAPI node for its core best block height (the Core gRPC
 * `getBestBlockHeight` call — the same Core service the app already uses
 * for `broadcastTransaction`/`getTransaction`; the legacy dashj-platform
 * [org.dashj.platform.dapiclient.DapiClient] does not wrap this method,
 * so the blocking stub is used directly) and compares it against the
 * local dashj chain tip from [BlockchainStateProvider].
 *
 * Every failure — platform unsupported, no masternode list yet, gRPC
 * error, timeout, or a static-initializer Error from the legacy gRPC
 * transport — degrades to [PlatformHealth.UNKNOWN]. The probe never throws
 * except for [CancellationException], which must propagate.
 */
@Singleton
class PlatformHealthProbe @Inject constructor(
    private val platformService: PlatformService,
    private val blockchainStateProvider: BlockchainStateProvider
) {
    companion object {
        private val log = LoggerFactory.getLogger(PlatformHealthProbe::class.java)
        private const val PROBE_TIMEOUT_MS = 10_000L
    }

    suspend fun probe(): PlatformHealth = withContext(Dispatchers.IO) {
        try {
            if (!Constants.SUPPORTS_PLATFORM) {
                return@withContext PlatformHealth.UNKNOWN
            }
            val localHeight = blockchainStateProvider.getState()?.bestChainHeight?.toLong() ?: 0L
            val platformCoreHeight = fetchDapiNodeCoreHeight()
            val health = assessPlatformHealth(platformCoreHeight, localHeight)
            log.info(
                "platform health probe: dapi node core height={}, local chain height={} -> {}",
                platformCoreHeight, localHeight, health
            )
            health
        } catch (t: Throwable) {
            // Cancellation is control flow, not a probe failure — it MUST
            // propagate or structured concurrency breaks.
            if (t is CancellationException) throw t
            if (t is Exception) {
                // Advisory only: a failed probe says nothing about the network.
                log.warn("platform health probe failed: {}", t.toString())
            } else {
                // MO-973: this used to catch only Exception, and the legacy
                // gRPC stack fails with an ERROR, not an exception —
                // ExceptionInInitializerError out of
                // NettyChannelBuilder.<clinit> when R8 strips a member its
                // static init reaches by reflection. That sailed past this
                // catch, past the caller's bare try/finally
                // (RequestUserNameViewModel.checkNetworkHealth), out of the
                // coroutine, and killed the process — 11 times on one HONOR
                // PTP-N49, which QA saw as a username-creation crash, a
                // username-creation failure, and "the lock screen appears
                // after clicking Continue" (the relaunch).
                //
                // An ADVISORY row must never be able to do that, whatever
                // breaks underneath it. Logged at ERROR with the full stack
                // rather than swallowed quietly: degrading silently would
                // trade a loud crash for an invisible one, and the stack is
                // what made this diagnosable from a QA report.
                log.error("platform health probe hit a non-Exception failure; degrading to UNKNOWN", t)
            }
            PlatformHealth.UNKNOWN
        }
    }

    private fun fetchDapiNodeCoreHeight(): Long? {
        val address = platformService.client.dapiAddressListProvider.getLiveAddress() ?: return null
        // Testnet/devnet DAPI nodes commonly present self-signed certificates.
        val allowSelfSigned = platformService.params.id != NetworkParameters.ID_MAINNET
        val masternode = DAPIGrpcMasternode(address, PROBE_TIMEOUT_MS, allowSelfSigned)
        return try {
            masternode.core
                .getBestBlockHeight(CoreOuterClass.GetBestBlockHeightRequest.newBuilder().build())
                .height
                .toLong()
        } finally {
            masternode.shutdown()
        }
    }
}
