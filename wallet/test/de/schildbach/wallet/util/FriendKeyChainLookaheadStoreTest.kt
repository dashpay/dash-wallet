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

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The side store that stops the DashPay friend-chain lookahead being re-derived
 * on every launch.
 *
 * Its contract is deliberately one-sided: a good file must round-trip exactly,
 * and ANY damaged, truncated, mis-versioned or foreign file must decode to
 * NOTHING rather than to something plausible — a wrong key here would be a
 * missed payment, so the store is only ever allowed to be right or absent.
 */
class FriendKeyChainLookaheadStoreTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("friend-lookahead-store").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun leaf(index: Int) = CachedLookaheadLeaf(
        index,
        ByteArray(33) { (index + it).toByte() },
        ByteArray(32) { (index * 2 + it).toByte() }
    )

    private fun chain(tag: Int, indexes: IntRange) = CachedLookaheadChain(
        ByteArray(33) { (tag + it).toByte() },
        ByteArray(32) { (tag + it).toByte() },
        indexes.map(::leaf)
    )

    // ── codec ─────────────────────────────────────────────────────────────

    @Test
    fun roundTrip_preservesEveryByte() {
        val original = listOf(chain(1, 2..132), chain(9, 5..40))

        val decoded = FriendKeyChainLookaheadStore.decode(FriendKeyChainLookaheadStore.encode(original))

        assertEquals(2, decoded.size)
        original.zip(decoded).forEach { (a, b) ->
            assertArrayEquals(a.accountPubKey, b.accountPubKey)
            assertArrayEquals(a.accountChainCode, b.accountChainCode)
            assertEquals(a.id, b.id)
            assertEquals(a.leaves.size, b.leaves.size)
            a.leaves.zip(b.leaves).forEach { (x, y) ->
                assertEquals(x.index, y.index)
                assertArrayEquals(x.pubKey, y.pubKey)
                assertArrayEquals(x.chainCode, y.chainCode)
            }
        }
    }

    @Test
    fun emptyStore_roundTripsAsEmpty() {
        assertTrue(FriendKeyChainLookaheadStore.decode(FriendKeyChainLookaheadStore.encode(emptyList())).isEmpty())
    }

    @Test
    fun corruptedByte_isRejectedByTheChecksum() {
        val bytes = FriendKeyChainLookaheadStore.encode(listOf(chain(1, 2..132)))
        // Flip a bit deep inside a stored public key — the case that would
        // otherwise install a key no derivation would ever produce.
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x01).toByte()

        assertTrue(FriendKeyChainLookaheadStore.decode(bytes).isEmpty())
    }

    @Test
    fun truncatedFile_isRejected() {
        val bytes = FriendKeyChainLookaheadStore.encode(listOf(chain(1, 2..132)))
        assertTrue(FriendKeyChainLookaheadStore.decode(bytes.copyOf(bytes.size - 40)).isEmpty())
        assertTrue(FriendKeyChainLookaheadStore.decode(ByteArray(4)).isEmpty())
        assertTrue(FriendKeyChainLookaheadStore.decode(ByteArray(0)).isEmpty())
    }

    @Test
    fun foreignOrFutureFile_isRejected() {
        val bytes = FriendKeyChainLookaheadStore.encode(listOf(chain(1, 2..10)))

        val alienMagic = bytes.copyOf()
        alienMagic[0] = 0x00
        assertTrue(FriendKeyChainLookaheadStore.decode(alienMagic).isEmpty())

        val futureVersion = bytes.copyOf()
        futureVersion[7] = (FriendKeyChainLookaheadStore.VERSION + 1).toByte()
        assertTrue(FriendKeyChainLookaheadStore.decode(futureVersion).isEmpty())
    }

    // ── file I/O ──────────────────────────────────────────────────────────

    @Test
    fun writeThenRead_roundTripsThroughTheFilesystem() {
        val file = File(dir, "wallet.friendlookahead")
        val chains = listOf(chain(3, 2..132))

        assertTrue(FriendKeyChainLookaheadStore.write(file, chains))
        assertTrue(file.exists())

        val read = FriendKeyChainLookaheadStore.read(file)
        assertEquals(1, read.size)
        assertEquals(chains[0].id, read[0].id)
        assertEquals(131, read[0].leaves.size)
        // No temp file is left behind.
        assertFalse(File(dir, "wallet.friendlookahead.tmp").exists())
    }

    @Test
    fun writeIsAtomicAndOverwrites() {
        val file = File(dir, "wallet.friendlookahead")
        FriendKeyChainLookaheadStore.write(file, listOf(chain(3, 2..132)))
        FriendKeyChainLookaheadStore.write(file, listOf(chain(4, 2..10)))

        val read = FriendKeyChainLookaheadStore.read(file)
        assertEquals(1, read.size)
        assertEquals(chain(4, 2..10).id, read[0].id)
    }

    @Test
    fun missingOrGarbageFile_readsAsEmpty_neverThrows() {
        assertTrue(FriendKeyChainLookaheadStore.read(File(dir, "absent")).isEmpty())

        val garbage = File(dir, "garbage")
        garbage.writeBytes(ByteArray(500) { it.toByte() })
        assertTrue(FriendKeyChainLookaheadStore.read(garbage).isEmpty())

        // A directory where a file is expected must not blow up either.
        assertTrue(FriendKeyChainLookaheadStore.read(dir).isEmpty())
    }

    // ── identity and staleness ────────────────────────────────────────────

    @Test
    fun identity_isTheAccountKey_soAnEntryCannotBeAppliedToTheWrongChain() {
        val a = chain(1, 2..10)
        val b = chain(2, 2..10)
        assertTrue(a.id != b.id)
        assertTrue(a.matchesAccount(a.accountPubKey, a.accountChainCode))
        assertFalse(a.matchesAccount(b.accountPubKey, b.accountChainCode))
        assertFalse(a.matchesAccount(a.accountPubKey, b.accountChainCode))
    }

    @Test
    fun signature_changesWhenTheWindowMoves() {
        val base = chain(1, 2..132).signature
        assertEquals(base, chain(1, 2..132).signature)
        assertTrue(base != chain(1, 2..133).signature) // window grew
        assertTrue(base != chain(1, 3..132).signature) // window slid (a key was issued)
        assertEquals("0", CachedLookaheadChain(ByteArray(33), ByteArray(32), emptyList()).signature)
    }

    @Test
    fun storeFileFor_sitsBesideTheWalletFile_notInsideIt() {
        val wallet = File(dir, "wallet-protobuf")
        val store = FriendKeyChainLookaheadStore.storeFileFor(wallet)
        assertEquals(dir, store.parentFile)
        assertEquals("wallet-protobuf" + FriendKeyChainLookaheadStore.FILE_SUFFIX, store.name)
        assertNotNull(store.parentFile)
    }
}
