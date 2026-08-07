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

package de.schildbach.wallet.util

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import org.bitcoinj.core.Utils
import org.slf4j.LoggerFactory

/** One derived, unissued DashPay friend-chain lookahead leaf. */
internal class CachedLookaheadLeaf(
    /** The leaf's (non-hardened) child index under the chain's account key. */
    val index: Int,
    /** 33-byte compressed public key. */
    val pubKey: ByteArray,
    /** 32-byte chain code. */
    val chainCode: ByteArray
)

/**
 * The lookahead window of ONE friend key chain, identified by the chain's
 * account key. Public material only — lookahead keys are derived by
 * `dropPrivateBytes()` and never carry a private key.
 */
internal class CachedLookaheadChain(
    val accountPubKey: ByteArray,
    val accountChainCode: ByteArray,
    /** Ascending by [CachedLookaheadLeaf.index]. */
    val leaves: List<CachedLookaheadLeaf>
) {
    /** Identity of the owning chain — unique per (seed/xpub, account path). */
    val id: String = FriendKeyChainLookaheadStore.idOf(accountPubKey, accountChainCode)

    /**
     * Cheap content stamp used to decide whether the on-disk copy is stale.
     * Two windows with the same leaf count and the same first/last index over
     * the same account key hold the same keys — the leaves are a deterministic
     * function of (account key, index).
     */
    val signature: String = signatureOf(leaves)

    fun matchesAccount(pubKey: ByteArray, chainCode: ByteArray): Boolean =
        accountPubKey.contentEquals(pubKey) && accountChainCode.contentEquals(chainCode)

    companion object {
        fun signatureOf(leaves: List<CachedLookaheadLeaf>): String =
            if (leaves.isEmpty()) "0" else "${leaves.size}@${leaves.first().index}-${leaves.last().index}"
    }
}

/**
 * The on-disk side store for DashPay friend-chain lookahead keys — the half of
 * the 11.10.61 fix that stops the 100+33-key-per-contact window being
 * RE-DERIVED on every single launch.
 *
 * ## Why a side file and not the wallet protobuf
 *
 * 11.10.58 made the deferred chains STRIP their unissued lookahead leaves when
 * serializing (`FriendKeyChainLookahead.stripUnissuedLookaheadLeaves`) because
 * keeping them in the wallet protobuf took the mainnet tester's file from
 * 2.5 MB to 6.5 MB and his autosaves from 34 s to 114 s. That fix is correct
 * and stays — but it is exactly why every launch re-derived 28,165 keys
 * (159 s on four threads, the ANR window).
 *
 * The two goals are only in tension while there is ONE file. The wallet
 * protobuf is rewritten on EVERY autosave, is parsed on the launch critical
 * path with a ~8x heap multiplier, and is what the backup/restore paths copy —
 * so anything bulky in it is paid for continuously. This store is a separate
 * file that is
 *
 *  * written only when its CONTENT changes (a window only shifts when a key is
 *    issued or a contact is added — typically never during a session), so it
 *    costs nothing per autosave;
 *  * read once per launch, off the main thread, inside the existing completion
 *    pass; and
 *  * purely an ACCELERATOR: it holds no private material, and a missing,
 *    truncated, corrupt, stale or mismatched store simply falls back to
 *    deriving. It can never introduce a key that derivation would not have
 *    produced (see `FriendKeyChainLookahead.installFromStore`, which
 *    re-derives and compares the first and last leaf before trusting any of
 *    them, and drops the whole entry on the slightest mismatch).
 *
 * ## Format
 *
 * ```
 * int   magic  = 'F''K''L''A'
 * int   version
 * int   payloadLength
 * long  CRC32(payload)
 * payload:
 *   int chainCount
 *   per chain: blob accountPubKey, blob accountChainCode, int leafCount,
 *              per leaf: int index, blob pubKey, blob chainCode
 * ```
 * where a blob is a `short` length followed by that many bytes. Every decode
 * bound is checked; anything that does not parse cleanly yields an empty store.
 */
internal object FriendKeyChainLookaheadStore {
    private val log = LoggerFactory.getLogger(FriendKeyChainLookaheadStore::class.java)

    /** 'F','K','L','A'. */
    const val MAGIC = 0x464B4C41
    const val VERSION = 1

    /** Sanity bounds — a malformed length must not allocate the heap away. */
    const val MAX_CHAINS = 100_000
    const val MAX_LEAVES_PER_CHAIN = 100_000
    const val MAX_BLOB_BYTES = 128
    const val MAX_FILE_BYTES = 64L * 1024 * 1024

    private const val HEADER_BYTES = 4 + 4 + 4 + 8

    /** The suffix appended to the wallet file name to name the store. */
    const val FILE_SUFFIX = ".friendlookahead"

    @JvmStatic
    fun storeFileFor(walletFile: File): File =
        File(walletFile.parentFile, walletFile.name + FILE_SUFFIX)

    fun idOf(accountPubKey: ByteArray, accountChainCode: ByteArray): String =
        Utils.HEX.encode(accountPubKey) + ":" + Utils.HEX.encode(accountChainCode)

    // ── codec (pure — unit tested directly) ───────────────────────────────

    fun encode(chains: Collection<CachedLookaheadChain>): ByteArray {
        val payloadBytes = ByteArrayOutputStream(1024)
        DataOutputStream(payloadBytes).use { out ->
            out.writeInt(chains.size)
            for (chain in chains) {
                writeBlob(out, chain.accountPubKey)
                writeBlob(out, chain.accountChainCode)
                out.writeInt(chain.leaves.size)
                for (leaf in chain.leaves) {
                    out.writeInt(leaf.index)
                    writeBlob(out, leaf.pubKey)
                    writeBlob(out, leaf.chainCode)
                }
            }
        }
        val payload = payloadBytes.toByteArray()
        val crc = CRC32().apply { update(payload) }.value

        val full = ByteArrayOutputStream(payload.size + HEADER_BYTES)
        DataOutputStream(full).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(payload.size)
            out.writeLong(crc)
            out.write(payload)
        }
        return full.toByteArray()
    }

    /** Decode [bytes]; ANY problem yields an empty list rather than an exception. */
    fun decode(bytes: ByteArray): List<CachedLookaheadChain> = try {
        decodeOrThrow(bytes)
    } catch (t: Throwable) {
        log.warn("friend key chain lookahead store is unreadable ({}) — falling back to derivation", t.toString())
        emptyList()
    }

    private fun decodeOrThrow(bytes: ByteArray): List<CachedLookaheadChain> {
        if (bytes.size < HEADER_BYTES) return emptyList()
        val input = DataInputStream(bytes.inputStream())
        val magic = input.readInt()
        if (magic != MAGIC) {
            log.warn("friend key chain lookahead store has a bad magic {} — ignoring", magic)
            return emptyList()
        }
        val version = input.readInt()
        if (version != VERSION) {
            log.info("friend key chain lookahead store is version {} (expected {}) — ignoring", version, VERSION)
            return emptyList()
        }
        val payloadLength = input.readInt()
        val crc = input.readLong()
        if (payloadLength < 0 || payloadLength != bytes.size - HEADER_BYTES) {
            log.warn("friend key chain lookahead store is truncated — ignoring")
            return emptyList()
        }
        val actualCrc = CRC32().apply { update(bytes, HEADER_BYTES, payloadLength) }.value
        if (actualCrc != crc) {
            log.warn("friend key chain lookahead store failed its checksum — ignoring")
            return emptyList()
        }

        val chainCount = input.readInt()
        if (chainCount < 0 || chainCount > MAX_CHAINS) throw IllegalStateException("chainCount $chainCount")
        val chains = ArrayList<CachedLookaheadChain>(chainCount)
        repeat(chainCount) {
            val accountPubKey = readBlob(input)
            val accountChainCode = readBlob(input)
            val leafCount = input.readInt()
            if (leafCount < 0 || leafCount > MAX_LEAVES_PER_CHAIN) throw IllegalStateException("leafCount $leafCount")
            val leaves = ArrayList<CachedLookaheadLeaf>(leafCount)
            var previousIndex = -1
            repeat(leafCount) {
                val index = input.readInt()
                if (index <= previousIndex) throw IllegalStateException("leaf indexes must ascend")
                previousIndex = index
                leaves.add(CachedLookaheadLeaf(index, readBlob(input), readBlob(input)))
            }
            chains.add(CachedLookaheadChain(accountPubKey, accountChainCode, leaves))
        }
        return chains
    }

    private fun writeBlob(out: DataOutputStream, bytes: ByteArray) {
        require(bytes.size <= MAX_BLOB_BYTES) { "blob too large: ${bytes.size}" }
        out.writeShort(bytes.size)
        out.write(bytes)
    }

    private fun readBlob(input: DataInputStream): ByteArray {
        val length = input.readUnsignedShort()
        if (length > MAX_BLOB_BYTES) throw IllegalStateException("blob length $length")
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return bytes
    }

    // ── file I/O (never throws to the caller) ─────────────────────────────

    fun read(file: File): List<CachedLookaheadChain> = try {
        when {
            !file.exists() -> emptyList()
            file.length() > MAX_FILE_BYTES -> {
                log.warn("friend key chain lookahead store is {} bytes — ignoring", file.length())
                emptyList()
            }
            else -> decode(file.readBytes())
        }
    } catch (t: Throwable) {
        log.warn("could not read the friend key chain lookahead store", t)
        emptyList()
    }

    /** Write atomically (temp file + rename). Returns true on success. */
    fun write(file: File, chains: Collection<CachedLookaheadChain>): Boolean {
        val temp = File(file.parentFile, file.name + ".tmp")
        return try {
            temp.writeBytes(encode(chains))
            if (!temp.renameTo(file)) {
                // Some filesystems refuse to rename onto an existing target.
                file.delete()
                if (!temp.renameTo(file)) throw java.io.IOException("rename failed: $temp -> $file")
            }
            true
        } catch (t: Throwable) {
            log.warn("could not write the friend key chain lookahead store", t)
            try {
                temp.delete()
            } catch (ignored: Throwable) {
                // best effort
            }
            false
        }
    }
}
