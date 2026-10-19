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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable storage of the highest Platform protocol version ever observed, one
 * value per network id. A seam so [ProtocolVersionLatch] is host-JVM testable
 * without Android's SharedPreferences.
 */
internal interface ProtocolVersionHighWaterStore {
    /** The stored high-water mark for [networkId], or null when none was ever stored. */
    fun read(networkId: String): Int?

    /** Store [version] as the high-water mark for [networkId]; visible to the next [read]. */
    fun write(networkId: String, version: Int)
}

/**
 * [ProtocolVersionHighWaterStore] in a DEDICATED SharedPreferences file — not
 * the app's default preferences, which Reset Wallet clears
 * (`Configuration.clearPreservingAnalytics`). The protocol version is a property
 * of the NETWORK, not of the wallet: a reset must not forget that the network
 * already runs a newer protocol. The file is opened lazily, on first use, so
 * constructing the store (and everything that injects it) touches no disk.
 */
internal class SharedPreferencesProtocolVersionHighWaterStore(
    context: Context
) : ProtocolVersionHighWaterStore {
    private val prefs by lazy { context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE) }

    override fun read(networkId: String): Int? =
        prefs.getInt(key(networkId), NONE).takeIf { it > 0 }

    override fun write(networkId: String, version: Int) {
        // apply(): the in-memory map is updated synchronously (the next read
        // sees it); only the disk write is asynchronous.
        prefs.edit().putInt(key(networkId), version).apply()
    }

    private fun key(networkId: String) = "highest_protocol_version_$networkId"

    companion object {
        const val PREFS_FILE_NAME = "platform_protocol_version"
        private const val NONE = 0
    }
}

/**
 * A monotonic latch over the network's live Platform protocol version: once
 * a version has been observed on a network, nothing read later can report an
 * OLDER one.
 *
 * ## Why
 *
 * The contested-username fee (MO-1069, [ContestedUsernameFees]) is quoted —
 * and approved by the user in the confirmation dialog — from one protocol
 * version read, and the asset lock is funded from ANOTHER read taken later,
 * on a different code path (TopUpRepository, CreateIdentityService's resumed
 * top-up, the SDK username/invite creations). Every read is a fresh DAPI query
 * that may land on a different node. A node that lags behind an activation, or
 * serves stale epoch info, reports 13 after the quote saw 14: without this
 * latch the wallet then funds the LEGACY 0.25 DASH after the user approved the
 * CURRENT 0.15 DASH.
 *
 * Platform protocol activation is one-way: a network's protocol version only
 * ever increases. So a remembered higher version is never wrong, and serving
 * `max(live, highest observed)` makes the quote and the funding agree whenever
 * the quote saw the newer version. The remaining divergence — a quote at 13
 * followed by a funding at 14 — funds LESS than was approved, which is safe.
 *
 * ## Contract ([latch])
 *
 * - A live read succeeded: return `max(live, highWater)`, raising (and
 *   persisting) the high-water mark when `live` is higher. A fresh activation
 *   (13 → 14) is therefore observed on the very next read, without a restart —
 *   the latch only ever refuses to go DOWN.
 * - The live read failed (null): return the high-water mark ONLY when it is at
 *   or above [CONTESTED_FEE_PROTOCOL_VERSION]. A network known to run the
 *   current protocol cannot have gone back, so funding may proceed at the
 *   lower CURRENT fee. Below that the newest behaviour may have activated
 *   since, so an unknown version stays unknown (null) and
 *   [ContestedUsernameFees.resolved] still refuses to fund the higher LEGACY
 *   fee from a stale memory.
 *
 * The mark is keyed by network id ([Constants.NETWORK_PARAMETERS]), so
 * mainnet, testnet and each devnet never share a value, and it survives Reset
 * Wallet ([SharedPreferencesProtocolVersionHighWaterStore]). It is never
 * lowered: a devnet torn down and rebuilt under the same name at an older
 * protocol would keep the newer mark — the cost is a contested registration
 * underfunded by the fee difference (Drive rejects it; nothing is overpaid).
 *
 * Construction is side-effect-free (no disk, no `Constants`), so it can be
 * injected into [DashSdkServiceImpl] without breaking that class's laziness
 * contract; the stored value is loaded on the first [latch] call, off the main
 * thread, then served from memory.
 */
@Singleton
class ProtocolVersionLatch internal constructor(
    private val store: ProtocolVersionHighWaterStore,
    private val networkId: () -> String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        store = SharedPreferencesProtocolVersionHighWaterStore(context),
        // Lazy: Constants untouched at construction.
        networkId = { Constants.NETWORK_PARAMETERS.id }
    )

    private val lock = Any()

    /** The high-water mark (0 = none); null until loaded from [store]. */
    @Volatile
    private var highWater: Int? = null

    /**
     * Combine a [live] protocol-version read with the highest version ever
     * observed on this network — see the class KDoc for the contract.
     */
    suspend fun latch(live: Int?): Int? {
        val high = withContext(ioDispatcher) {
            if (live != null) raiseTo(live) else loaded()
        }
        return when {
            live != null -> high
            high >= CONTESTED_FEE_PROTOCOL_VERSION -> high
            else -> null
        }
    }

    /** The current mark, loading it from [store] on first use. Never throws. */
    private fun loaded(): Int = highWater ?: synchronized(lock) {
        highWater ?: (readStored() ?: 0).also { highWater = it }
    }

    /** Raise the mark to [version] if higher; returns the (possibly raised) mark. */
    private fun raiseTo(version: Int): Int {
        val current = loaded()
        if (version <= current) return current
        return synchronized(lock) {
            val latest = highWater ?: 0
            if (version > latest) {
                highWater = version
                writeStored(version)
                version
            } else {
                latest
            }
        }
    }

    private fun readStored(): Int? = try {
        store.read(networkId())
    } catch (e: Exception) {
        log.warn("could not read the stored protocol-version high-water mark; treating it as none", e)
        null
    }

    private fun writeStored(version: Int) {
        try {
            store.write(networkId(), version)
        } catch (e: Exception) {
            // The in-memory mark still holds for this process.
            log.warn("could not persist the protocol-version high-water mark {}", version, e)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ProtocolVersionLatch::class.java)
    }
}
