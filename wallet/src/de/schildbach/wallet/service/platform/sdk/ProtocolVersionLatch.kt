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
import androidx.core.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import de.schildbach.wallet.Constants
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable storage of the highest Platform protocol version ever observed, one
 * value per network id. A seam so [ProtocolVersionLatch] is host-JVM testable.
 */
internal interface ProtocolVersionHighWaterStore {
    /**
     * The stored high-water mark for [networkId], or null ONLY when none was
     * ever stored. A mark that exists but can't be read or parsed must THROW:
     * the latch fails closed on it, and treating it as absent could let a stale
     * older version price funding after a durable newer one.
     */
    fun read(networkId: String): Int?

    /**
     * Durably store [version] as the high-water mark for [networkId]. Returns
     * true only once the value is on disk, so it survives a process kill; false
     * (or a throw) means it may not, and the caller must not rely on it.
     */
    fun write(networkId: String, version: Int): Boolean
}

/**
 * [ProtocolVersionHighWaterStore] as one small [AtomicFile] per network under
 * [directory] — a file this class reads and parses itself, so "absent" and
 * "unreadable" stay distinct. (SharedPreferences can't keep them apart: its
 * loader swallows an unreadable or corrupt file and serves an empty map, which
 * reads exactly like "never stored".)
 *
 * - [read]: no file → null; a file that can't be opened, read or parsed →
 *   throws.
 * - [write]: writes through [AtomicFile] (new file + rename), fsyncs the data
 *   and then the directory itself, and reads it back before returning true —
 *   [AtomicFile.finishWrite] only logs a failed rename, so success can't be
 *   taken from it.
 *
 * Lives in `noBackupFilesDir`, outside everything Reset Wallet deletes: the
 * protocol version is a property of the NETWORK, not of the wallet. [directory]
 * is resolved lazily, so constructing the store touches no disk.
 */
internal class AtomicFileProtocolVersionHighWaterStore(
    private val directory: () -> File
) : ProtocolVersionHighWaterStore {

    override fun read(networkId: String): Int? {
        val file = atomicFile(networkId)
        val bytes = try {
            file.readFully()
        } catch (e: FileNotFoundException) {
            // Absent only if the file is CONFIRMED missing. File.exists() is also
            // false when the path can't be looked up (EACCES/EIO on the directory),
            // which is unreadable, not absent; Files.notExists() is true only for
            // a confirmed ENOENT.
            if (!Files.notExists(file.baseFile.toPath())) throw e
            return null
        }
        val text = String(bytes, Charsets.UTF_8).trim()
        return text.toIntOrNull()?.takeIf { it > 0 }
            ?: throw IOException("unparseable protocol-version mark \"$text\" in ${file.baseFile}")
    }

    override fun write(networkId: String, version: Int): Boolean {
        val dir = directory()
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val file = atomicFile(networkId)
        val out = file.startWrite()
        try {
            out.write(version.toString().toByteArray(Charsets.UTF_8))
            out.fd.sync()
        } catch (e: IOException) {
            file.failWrite(out)
            throw e
        }
        file.finishWrite(out)
        syncDirectory(dir)
        return read(networkId) == version
    }

    /**
     * fsync [dir] so finishWrite's rename (and, on the first write, the new
     * entry) survives a power loss, not just a process kill — AtomicFile never
     * syncs the directory. Best effort: the data file itself is already synced,
     * and failing the write here on a platform that can't sync a directory
     * would leave the latch unable to ever persist a mark (contested funding
     * refused for good), which is worse than the narrow power-loss window.
     */
    private fun syncDirectory(dir: File) {
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (e: IOException) {
            log.warn("could not sync the protocol-version mark directory {}", dir, e)
        }
    }

    private fun atomicFile(networkId: String) =
        AtomicFile(File(directory(), networkId.replace(UNSAFE_FILE_NAME_CHARS, "_")))

    companion object {
        private val log = LoggerFactory.getLogger(AtomicFileProtocolVersionHighWaterStore::class.java)
        const val DIRECTORY_NAME = "platform_protocol_version"
        private val UNSAFE_FILE_NAME_CHARS = Regex("[^A-Za-z0-9._-]")
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
 * - The mark is DURABLE before it is served: it is raised only after the store
 *   confirms the write is on disk. A quote at 14 that a restarted process could
 *   forget would let that process fund the LEGACY fee from a stale 13 read, so
 *   when a version at or above [CONTESTED_FEE_PROTOCOL_VERSION] cannot be
 *   persisted, the read is treated like a failed one (next point) — fail closed:
 *   quotes show LEGACY and [ContestedUsernameFees.resolved] refuses to fund
 *   until a later read persists. An unpersisted older version is still returned
 *   as read: it selects the LEGACY fee anyway, the higher of the two.
 * - An UNREADABLE store is not "no mark": a previous process may have durably
 *   recorded 14, and treating it as absent would let a stale 13 read fund the
 *   LEGACY fee after a CURRENT confirmation. While the stored mark can't be
 *   read the latch returns null (fail closed, as above), and an older live
 *   version never overwrites it. A live version at or above
 *   [CONTESTED_FEE_PROTOCOL_VERSION] may replace it (durably, as above): every
 *   such mark prices the same CURRENT fee, so nothing the unreadable mark could
 *   hold is lost — and a corrupt file can't refuse contested funding forever.
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
 * Wallet ([AtomicFileProtocolVersionHighWaterStore]). It is never
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
        store = AtomicFileProtocolVersionHighWaterStore {
            File(context.noBackupFilesDir, AtomicFileProtocolVersionHighWaterStore.DIRECTORY_NAME)
        },
        // Lazy: Constants untouched at construction.
        networkId = { Constants.NETWORK_PARAMETERS.id }
    )

    private val lock = Any()

    /**
     * The DURABLE high-water mark (0 = none); null until loaded from [store].
     * Only ever set to a value the store confirmed on disk (or read from it).
     */
    @Volatile
    private var highWater: Int? = null

    /**
     * Combine a [live] protocol-version read with the highest version ever
     * observed on this network — see the class KDoc for the contract.
     */
    suspend fun latch(live: Int?): Int? {
        val mark = withContext(ioDispatcher) {
            if (live != null) raiseTo(live) else loaded()
        } ?: return null // the stored mark can't be read: fail closed
        return when {
            // Persisted (or already covered by the durable mark).
            live != null && live <= mark -> mark
            // Not persisted, but an older version selects the LEGACY (higher) fee anyway.
            live != null && live < CONTESTED_FEE_PROTOCOL_VERSION -> live
            // Unknown, or a current-era read that could not be persisted: only a
            // durable current-era mark can vouch for the version.
            mark >= CONTESTED_FEE_PROTOCOL_VERSION -> mark
            else -> null
        }
    }

    /**
     * The durable mark (0 = confirmed absent), loading it from [store] on first
     * use; null when the store can't be read — NOT the same as absent. Never
     * throws; an unreadable store is retried on the next call.
     */
    private fun loaded(): Int? = highWater ?: synchronized(lock) {
        highWater ?: readStored()?.also { highWater = it }
    }

    /**
     * Raise the durable mark to [version] if higher and the store confirms the
     * write; returns the durable mark afterwards (unchanged if the write failed).
     * When the current mark can't be read, returns null unless [version] is a
     * current-era version that durably replaces it (see the class KDoc).
     */
    private fun raiseTo(version: Int): Int? {
        val current = loaded()
        if (current != null && version <= current) return current
        if (current == null && version < CONTESTED_FEE_PROTOCOL_VERSION) return null
        return synchronized(lock) {
            val latest = highWater
            when {
                latest != null && version <= latest -> latest
                // latest == null: the unreadable mark, replaced only by a current-era version.
                writeStored(version) -> version.also { highWater = it }
                else -> latest
            }
        }
    }

    /** The stored mark (0 when none was ever stored), or null when the store can't be read. */
    private fun readStored(): Int? = try {
        store.read(networkId()) ?: 0
    } catch (e: Exception) {
        log.warn("could not read the stored protocol-version high-water mark; failing closed (version unknown until it can be read)", e)
        null
    }

    /** True only when the store confirmed [version] is on disk. */
    private fun writeStored(version: Int): Boolean {
        val persisted = try {
            store.write(networkId(), version)
        } catch (e: Exception) {
            log.warn("could not persist the protocol-version high-water mark {}", version, e)
            false
        }
        if (!persisted) {
            log.warn("protocol-version high-water mark {} not persisted; not relying on it", version)
        }
        return persisted
    }

    companion object {
        private val log = LoggerFactory.getLogger(ProtocolVersionLatch::class.java)
    }
}
