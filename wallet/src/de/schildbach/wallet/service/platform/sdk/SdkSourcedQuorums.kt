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

import com.google.gson.JsonParser
import de.schildbach.wallet.Constants
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Context
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.crypto.BLSPublicKey
import org.bitcoinj.evolution.SimplifiedMasternodeListManager
import org.bitcoinj.quorums.LLMQParameters
import org.bitcoinj.quorums.Quorum
import org.bitcoinj.quorums.SimplifiedQuorumList
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SDK-sourced quorums — the post-cutover quorum-key bridge (Phase 5d
 * follow-up, `docs/kotlin-sdk-migration-plan.md`).
 *
 * ## Why this exists
 *
 * The dashj-platform stack ("platform-mobile" rust) verifies every DAPI
 * response proof with a BLS quorum public key it obtains through a
 * [org.dashj.platform.sdk.callbacks.ContextProvider] callback that is
 * registered ONCE, inside the `DapiClient` constructor
 * (`platformMobileSdkCreateDashSdkWithContext`), and permanently bound to
 * `DapiClient`'s own internal provider — which answers from a lateinit
 * `SimplifiedMasternodeListManager` set only via
 * `Platform.setMasternodeListManager`.
 *
 * Pre-cutover that manager is dashj's own (wired in
 * `BlockchainServiceImpl.checkService()` when the peergroup starts). When
 * the Phase 5d cutover holds the dashj L1 engine, that wiring never runs:
 * every quorum callback died with the lateinit crash, every DAPI proof
 * verification failed ("quorum not found"), each DAPI address got banned
 * until `NoAvailableAddresses`, and DashPay went dark (observed live on
 * the cutover rehearsal).
 *
 * ## What this does
 *
 * [SdkQuorumDataSource.createMasternodeListManager] builds a
 * [SimplifiedMasternodeListManager] whose quorum list is synthesized from
 * the Kotlin SDK's `getCurrentQuorumsInfo` DAPI query (each active
 * validator-set quorum's `quorum_hash` + 48-byte BLS
 * `threshold_public_key`). That query is fetched UNPROVED over the Kotlin
 * SDK's own DAPI client, whose proofs are independently verified by the
 * SDK's `TrustedHttpContextProvider` — no dashj masternode list anywhere
 * in the chain, and no circular proof dependency.
 *
 * `BlockchainServiceImpl` hands this manager to
 * `platformRepo.platform.setMasternodeListManager(...)` when the cutover
 * gate resolves to "hold the dashj engine", which wires all three layers
 * at once: the app's own quorum callback, `DapiClient`'s rust-registered
 * internal provider, and the DAPI address provider (whose empty
 * masternode list makes it fall back to the network's default HP
 * masternode list — addresses stay available).
 *
 * ## Coverage caveat
 *
 * `getCurrentQuorumsInfo` returns the CURRENT platform validator-set
 * quorums. A proof signed by a quorum that rotated out long ago can still
 * miss; the cache merges every fetch (a quorum's key never changes for
 * its hash), so coverage grows across rotations. This matches the
 * trusted-provider "current + previous" model the Kotlin SDK itself uses.
 */

private val log = LoggerFactory.getLogger(SdkQuorumDataSource::class.java)

// ── pure helpers (host-testable, no native, no Android) ───────────────

/** Lowercase-hex decode; null unless [hex] is exactly [expectedBytes] bytes. */
internal fun decodeHexOrNull(hex: String, expectedBytes: Int): ByteArray? {
    val cleaned = hex.removePrefix("0x").removePrefix("0X")
    if (cleaned.length != expectedBytes * 2) return null
    val out = ByteArray(expectedBytes)
    for (i in out.indices) {
        val hi = Character.digit(cleaned[2 * i], 16)
        val lo = Character.digit(cleaned[2 * i + 1], 16)
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

/**
 * Parse the Kotlin SDK's `currentQuorumsInfo` JSON into a map of
 * lowercase 64-hex `quorum_hash` → 48-byte BLS `threshold_public_key`.
 *
 * Tolerant by design: a malformed document returns an empty map and a
 * malformed entry is skipped — a bad fetch must degrade to "quorum not
 * found" (retried later), never to a crash inside the DAPI callback.
 */
internal fun parseQuorumThresholdKeys(json: String): Map<String, ByteArray> {
    val result = LinkedHashMap<String, ByteArray>()
    try {
        val root = JsonParser.parseString(json)
        if (!root.isJsonObject) return emptyMap()
        val validatorSets = root.asJsonObject.get("validator_sets")
        if (validatorSets == null || !validatorSets.isJsonArray) return emptyMap()
        for (element in validatorSets.asJsonArray) {
            if (!element.isJsonObject) continue
            val obj = element.asJsonObject
            val hashHex = obj.get("quorum_hash")
                ?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            val keyHex = obj.get("threshold_public_key")
                ?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            val hash = decodeHexOrNull(hashHex, 32) ?: continue
            val key = decodeHexOrNull(keyHex, 48) ?: continue
            result[hash.toHexLower()] = key
        }
    } catch (e: Exception) {
        return emptyMap()
    }
    return result
}

private fun ByteArray.toHexLower(): String = joinToString("") { "%02x".format(it) }

/**
 * Merge-on-fetch cache for SDK-sourced quorum threshold keys.
 *
 * - A quorum's public key is immutable for its hash, so every successful
 *   fetch MERGES into the previous snapshot — quorums that rotate out
 *   stay resolvable for proofs signed shortly before rotation.
 * - Refresh policy at access time: an empty snapshot retries after
 *   [retryIntervalMs]; a populated one refreshes after
 *   [refreshIntervalMs]. Failures keep the previous keys and stamp the
 *   attempt so the DAPI callback can never hammer the fetcher.
 * - [snapshot] may block (single-flight) on the SDK query; callers are
 *   rust DAPI worker threads that are blocking on the quorum callback
 *   anyway.
 */
internal class SdkQuorumKeyCache(
    private val fetchQuorumsJson: suspend () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retryIntervalMs: Long = 30_000L,
    private val refreshIntervalMs: Long = 5 * 60_000L,
    private val maxEntries: Int = 512
) {
    private class State(val keys: Map<String, ByteArray>, val fetchedAtMs: Long)

    @Volatile
    private var state: State? = null
    private val fetchLock = Any()

    private fun isDue(current: State?, now: Long): Boolean = when {
        current == null -> true
        current.keys.isEmpty() -> now - current.fetchedAtMs >= retryIntervalMs
        else -> now - current.fetchedAtMs >= refreshIntervalMs
    }

    fun snapshot(): Map<String, ByteArray> {
        if (!isDue(state, clock())) {
            return state?.keys ?: emptyMap()
        }
        synchronized(fetchLock) {
            val current = state
            if (!isDue(current, clock())) {
                return current?.keys ?: emptyMap()
            }
            val fetched = try {
                runBlocking { fetchQuorumsJson() }?.let { parseQuorumThresholdKeys(it) }
                    ?: emptyMap()
            } catch (e: Exception) {
                log.warn("SDK quorum fetch failed (keeping {} cached key(s)): {}",
                    current?.keys?.size ?: 0, e.toString())
                emptyMap()
            }
            val merged = (current?.keys ?: emptyMap()) + fetched
            // A runaway merge means something is off (rotation churn far
            // beyond expectations) — reset to the latest fetch.
            val bounded = if (merged.size > maxEntries && fetched.isNotEmpty()) fetched else merged
            state = State(bounded, clock())
            if (fetched.isNotEmpty()) {
                log.info(
                    "SDK quorum keys refreshed: {} fetched, {} cached",
                    fetched.size, bounded.size
                )
            }
            return bounded
        }
    }
}

/**
 * A [SimplifiedMasternodeListManager] that never syncs: its quorum list
 * is synthesized from SDK-sourced threshold keys on every call. The
 * masternode list itself stays empty, which the DAPI address provider
 * treats as "use the default HP masternode list".
 *
 * Each quorum is added in BOTH byte orders of its hash: the callback
 * matches on exact `Sha256Hash` equality, and the orientation of the
 * 32-byte hash the rust side passes cannot be verified off-device.
 * Reversal is a bijection, so the extra entry can never shadow a
 * different quorum. The requested [LLMQParameters.LLMQType] is echoed
 * into each synthesized entry because the SDK data carries no LLMQ type —
 * matching is by hash; the type merely has to agree with the request.
 */
internal class SdkSourcedMasternodeListManager(
    context: Context,
    private val quorumKeys: () -> Map<String, ByteArray>
) : SimplifiedMasternodeListManager(context) {

    override fun getQuorumListAtTip(llmqType: LLMQParameters.LLMQType): SimplifiedQuorumList {
        val list = SimplifiedQuorumList(params)
        val keys = try {
            quorumKeys()
        } catch (e: Exception) {
            log.warn("SDK quorum key lookup failed; serving an empty quorum list", e)
            emptyMap()
        }
        val llmqParameters = try {
            LLMQParameters.fromType(llmqType)
        } catch (e: Exception) {
            log.warn("no LLMQ parameters for requested type {}; serving an empty quorum list", llmqType)
            return list
        }
        for ((hashHex, key) in keys) {
            try {
                // Basic (non-legacy) BLS scheme — active on both mainnet
                // and testnet; serialize(false) round-trips these 48 bytes.
                val publicKey = BLSPublicKey(key, false)
                val hash = Sha256Hash.wrap(hashHex)
                list.addQuorum(Quorum(params, llmqParameters, hash, publicKey))
                list.addQuorum(
                    Quorum(params, llmqParameters, Sha256Hash.wrapReversed(hash.bytes), publicKey)
                )
            } catch (e: Exception) {
                log.warn("skipping malformed SDK quorum entry {}…: {}", hashHex.take(16), e.toString())
            }
        }
        return list
    }
}

/**
 * Injectable facade: SDK-sourced quorum threshold keys plus the
 * [SimplifiedMasternodeListManager] bridge that serves them to the
 * dashj-platform stack. Construction is inert (no native, no fetch);
 * everything happens lazily on first quorum lookup.
 */
@Singleton
class SdkQuorumDataSource @Inject constructor(
    private val dashSdkService: DashSdkService
) {
    private val cache = SdkQuorumKeyCache(
        fetchQuorumsJson = {
            // Only serve from an already-running SDK: post-cutover the SDK
            // owns L1 and is brought up by the platform-sync bootstrap, so
            // triggering a full native bootstrap from a rust DAPI callback
            // thread here would be both surprising and unnecessary.
            dashSdkService.sdkOrNull()?.system?.currentQuorumsInfo().also {
                if (it == null && !dashSdkService.isStarted) {
                    log.info("SDK quorum fetch skipped: SDK not started yet")
                }
            }
        }
    )

    /** Current SDK-sourced quorum keys (may fetch, contained failures). */
    fun quorumThresholdKeys(): Map<String, ByteArray> = cache.snapshot()

    /**
     * The bridge manager to hand to
     * `platformRepo.platform.setMasternodeListManager(...)` post-cutover.
     */
    fun createMasternodeListManager(): SimplifiedMasternodeListManager =
        SdkSourcedMasternodeListManager(Constants.CONTEXT) { quorumThresholdKeys() }
}
