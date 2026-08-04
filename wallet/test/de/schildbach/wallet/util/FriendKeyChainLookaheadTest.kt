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

import com.google.common.collect.ImmutableList
import java.math.BigInteger
import org.bitcoinj.core.Utils
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.ExtendedChildNumber
import org.bitcoinj.script.Script
import org.bitcoinj.wallet.DerivationPathFactory
import org.bitcoinj.wallet.DeterministicKeyChain
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.FriendKeyChain
import org.bitcoinj.wallet.KeyChain
import org.bitcoinj.wallet.Protos
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The DashPay friend-key-chain deferral — the fix for the launch crash loop
 * where `WalletProtobufSerializer.readWallet` spent ~1.2 s PER CONTACT chain
 * deriving 131 lookahead keys inside `Application.onCreate`.
 *
 * The load-bearing test is [deferredChain_afterCompletion_ownsExactlyTheEagerKeySet]:
 * the funds-safety proof that deferring changes only WHEN the keys are derived,
 * never WHICH.
 *
 * Note on dashj mechanics these tests depend on:
 *  * a fresh [FriendKeyChain] already carries lookaheadSize 100 / threshold 33
 *    (the values the tester's log reports) and derives NOTHING in its
 *    constructor — the bulk derivation happens only in `maybeLookAhead()`;
 *  * `numKeys()` and `getFilter()` themselves call `maybeLookAhead()`, so they
 *    both force the eager behaviour and observe the deferred one;
 *  * `getLeafKeys()` deliberately EXCLUDES lookahead keys, so it is not a usable
 *    probe here — `serializeToProtobuf()` and `findKeyFromPubHash()` are.
 */
class FriendKeyChainLookaheadTest {

    private companion object {
        /**
         * The derivation the tester's log reports, exactly: a persisted DashPay
         * friend chain carries 2 children, and dashj's lookahead (size 100 +
         * threshold 33) therefore needs `0 issued + 100 + 33 - 2` more keys.
         */
        const val EXPECTED_DERIVED_KEYS = 131
    }

    // A fixed 16-byte entropy — the tests must be deterministic.
    private val seedEntropy = Utils.HEX.decode("000102030405060708090a0b0c0d0e0f")

    private fun seed() = DeterministicSeed(seedEntropy, "", 1_600_000_000L)

    /**
     * A DashPay friend path shape: `9' / 5' / 15'(DASHPAY) / account' / a / b`.
     * The real chains carry 256-bit user ids (8 ChildNumbers each); only the
     * first three levels are what dashj — and therefore
     * [FriendKeyChainLookahead.isDashPayFriendPath] — tests on.
     */
    private fun dashPayPath(leafA: Int = 7, leafB: Int = 9): ImmutableList<ChildNumber> = ImmutableList.of(
        ChildNumber.NINE_HARDENED,
        ChildNumber(5, true),
        DerivationPathFactory.FEATURE_PURPOSE_DASHPAY,
        ChildNumber(0, true),
        ChildNumber(leafA, false),
        ChildNumber(leafB, false)
    )

    /** Every public key the chain currently holds — including the lookahead buffer. */
    private fun publicKeys(chain: DeterministicKeyChain): List<List<Byte>> =
        chain.serializeToProtobuf()
            .filter { it.hasPublicKey() }
            .map { it.publicKey.toByteArray().toList() }

    @Before
    fun setUp() = FriendKeyChainLookahead.reset()

    @After
    fun tearDown() = FriendKeyChainLookahead.reset()

    // ── isDashPayFriendPath: exactly dashj's own DefaultKeyChainFactory test ──

    @Test
    fun dashPayFriendPath_isRecognised() {
        assertTrue(FriendKeyChainLookahead.isDashPayFriendPath(dashPayPath()))
    }

    @Test
    fun nonDashPayPaths_areNotDeferred() {
        // BIP44 (44'/5'/0') — the wallet's own funds chain, must never be touched.
        assertFalse(
            FriendKeyChainLookahead.isDashPayFriendPath(
                ImmutableList.of(ChildNumber(44, true), ChildNumber(5, true), ChildNumber(0, true))
            )
        )
        // 9'/5'/x' with a non-DashPay feature purpose (blockchain identity etc.).
        assertFalse(
            FriendKeyChainLookahead.isDashPayFriendPath(
                ImmutableList.of(ChildNumber.NINE_HARDENED, ChildNumber(5, true), ChildNumber(5, true))
            )
        )
        // Too short to be a friend path — must not throw, must not defer.
        assertFalse(FriendKeyChainLookahead.isDashPayFriendPath(ImmutableList.of(ChildNumber.NINE_HARDENED)))
        assertFalse(FriendKeyChainLookahead.isDashPayFriendPath(ImmutableList.of()))
        assertFalse(FriendKeyChainLookahead.isDashPayFriendPath(null))
    }

    // ── parallelism sizing ────────────────────────────────────────────────

    @Test
    fun parallelism_leavesACoreForTheForegroundAndIsCapped() {
        assertEquals(0, FriendKeyChainLookahead.parallelism(cores = 8, chains = 0))
        assertEquals(1, FriendKeyChainLookahead.parallelism(cores = 1, chains = 100))
        assertEquals(1, FriendKeyChainLookahead.parallelism(cores = 2, chains = 100))
        assertEquals(3, FriendKeyChainLookahead.parallelism(cores = 4, chains = 100))
        // Capped at MAX_PARALLELISM however many cores the device has.
        assertEquals(
            FriendKeyChainLookahead.MAX_PARALLELISM,
            FriendKeyChainLookahead.parallelism(cores = 16, chains = 100)
        )
        // Never more threads than there is work.
        assertEquals(2, FriendKeyChainLookahead.parallelism(cores = 16, chains = 2))
    }

    // ── the deferring factory ─────────────────────────────────────────────

    private fun receivingChain(path: ImmutableList<ChildNumber>): DeterministicKeyChain =
        FriendKeyChainLookahead.deferringFactory().makeSpendingFriendKeyChain(
            Protos.Key.getDefaultInstance(), null, seed(), null, false, path
        )

    @Test
    fun deferringFactory_queuesDashPayFriendChains_andDerivesNothingUpFront() {
        val eagerKeyCount = FriendKeyChain(seed(), null, dashPayPath()).numKeys()
        assertTrue("sanity: the eager chain derives its lookahead", eagerKeyCount > EXPECTED_DERIVED_KEYS)

        val chain = receivingChain(dashPayPath())

        assertTrue("must still be a dashj FriendKeyChain", chain is FriendKeyChain)
        assertEquals(1, FriendKeyChainLookahead.deferredCount())
        assertEquals(1, FriendKeyChainLookahead.pendingCount())

        // The whole point: `fromProtobuf`'s maybeLookAhead() is inert, so the
        // parse costs nothing per contact chain. (numKeys() itself calls
        // maybeLookAhead — this asserts that call did not derive.)
        chain.maybeLookAhead()
        assertTrue(
            "the deferred chain must not derive its lookahead during the parse",
            chain.numKeys() < EXPECTED_DERIVED_KEYS
        )
    }

    @Test
    fun deferringFactory_leavesNonDashPayChainsAlone() {
        val chain = FriendKeyChainLookahead.deferringFactory().makeKeyChain(
            Protos.Key.getDefaultInstance(),
            null,
            seed(),
            null,
            false,
            Script.ScriptType.P2PKH,
            ImmutableList.of(ChildNumber(44, true), ChildNumber(5, true), ChildNumber(0, true))
        )
        assertEquals("the wallet's own funds chains must never be deferred", 0, FriendKeyChainLookahead.deferredCount())
        // …and it behaves exactly as dashj built it: lookahead runs eagerly.
        assertTrue(chain.numKeys() > EXPECTED_DERIVED_KEYS)
    }

    // ── FUNDS SAFETY: the same keys, later ───────────────────────────────

    @Test
    fun deferredChain_afterCompletion_ownsExactlyTheEagerKeySet() {
        val path = dashPayPath()

        // What dashj does today, inside the parse.
        val eager = FriendKeyChain(seed(), null, path)
        eager.maybeLookAhead()
        val eagerKeys = publicKeys(eager)
        assertEquals(
            "sanity: 0 issued + 100 lookahead size + 33 threshold - 2 num children — the log's own arithmetic",
            EXPECTED_DERIVED_KEYS, eagerKeys.size - publicKeys(FriendKeyChain(seed(), null, path)).size
        )

        // What this fix does: nothing during the parse, all of it after.
        val deferred = receivingChain(path) as FriendKeyChain
        assertTrue(deferred.numKeys() < EXPECTED_DERIVED_KEYS)

        assertTrue("completion must finish", FriendKeyChainLookahead.awaitComplete(60_000L))
        assertEquals(1, FriendKeyChainLookahead.completedCount())
        assertEquals(0, FriendKeyChainLookahead.pendingCount())

        // The watched-key SET must be identical — only its timing moves.
        // (Compared through the chains' IN-MEMORY key ownership: the deferred
        // chain deliberately serializes a smaller protobuf — see the
        // serialization-strip tests — so the file is no longer the probe.)
        assertEquals(
            "the in-memory key count must match the eager chain",
            eager.numKeys(), deferred.numKeys()
        )

        // The funds-detection primitive itself: every address the eager chain
        // would have recognised, the completed deferred chain recognises.
        eagerKeys.forEach {
            assertNotNull(
                "a completed deferred chain must own every eager key",
                deferred.findKeyFromPubKey(it.toByteArray())
            )
        }
    }

    /**
     * The gap the blockchain-service gate exists to close: DURING the deferral
     * window a far-out lookahead address is genuinely not yet owned, which is
     * why `BlockchainServiceImpl` awaits completion before
     * `PeerGroup.addWallet(wallet)` — no bloom filter is ever built from a
     * partially-provisioned chain.
     */
    @Test
    fun duringTheDeferralWindow_farLookaheadKeysAreNotYetOwned_andTheGateClosesThat() {
        val path = dashPayPath()
        val eager = FriendKeyChain(seed(), null, path)
        eager.maybeLookAhead()
        val lastLookaheadKey = eager.serializeToProtobuf().last { it.hasPublicKey() }.publicKey.toByteArray()

        val deferred = receivingChain(path) as FriendKeyChain
        assertNull(
            "precondition: the deferral really does leave the window unpopulated",
            deferred.findKeyFromPubKey(lastLookaheadKey)
        )

        assertTrue(FriendKeyChainLookahead.awaitComplete(60_000L))

        assertNotNull(
            "after the gate the full window is owned again",
            deferred.findKeyFromPubKey(lastLookaheadKey)
        )
    }

    @Test
    fun completion_isIdempotent_andASecondPassAddsNothing() {
        val deferred = receivingChain(dashPayPath())
        assertTrue(FriendKeyChainLookahead.awaitComplete(60_000L))
        val afterFirst = publicKeys(deferred)

        FriendKeyChainLookahead.completeAsync()
        assertTrue(FriendKeyChainLookahead.awaitComplete(60_000L))
        assertEquals(afterFirst, publicKeys(deferred))
    }

    @Test
    fun completion_derivesEveryQueuedChain() {
        val chains = (0 until 5).map { receivingChain(dashPayPath(leafA = it)) }
        assertEquals(5, FriendKeyChainLookahead.deferredCount())

        assertTrue(FriendKeyChainLookahead.awaitComplete(120_000L))

        assertEquals(5, FriendKeyChainLookahead.completedCount())
        assertEquals(0, FriendKeyChainLookahead.pendingCount())
        chains.forEach { assertTrue(it.numKeys() > EXPECTED_DERIVED_KEYS) }
        // Distinct contacts must derive distinct keys (no path collapsing).
        assertNotEquals(publicKeys(chains[0]), publicKeys(chains[1]))
        assertTrue("completion time must be recorded", FriendKeyChainLookahead.completionMs() >= 0)
    }

    /**
     * ON-DEMAND issuance must be unaffected by the deferral.
     * `FriendKeyChain.getKeys` derives what it hands out straight from the
     * chain's `hierarchy` and only ever calls the PROTECTED four-argument
     * `maybeLookAhead(parent, issued, 0, 0)` — never the no-arg method the
     * deferral overrides — so an address issued to a contact during the
     * deferral window is byte-identical to one issued today.
     */
    @Test
    fun keyIssuanceDuringTheDeferralWindow_isByteIdenticalToTheEagerChain() {
        val deferred = receivingChain(dashPayPath()) as FriendKeyChain
        val eager = FriendKeyChain(seed(), null, dashPayPath())

        repeat(3) { i ->
            val issued = deferred.getKeys(KeyChain.KeyPurpose.RECEIVE_FUNDS, 1)
            assertEquals(1, issued.size)
            assertEquals(
                "issued key #$i must match the eager chain",
                eager.getKeys(KeyChain.KeyPurpose.RECEIVE_FUNDS, 1)[0].pubKeyHash.toList(),
                issued[0].pubKeyHash.toList()
            )
        }
        assertEquals(3, deferred.issuedExternalKeys)
    }

    // ── the serialization strip: lookahead keys do NOT round-trip ─────────

    /** Serialized DETERMINISTIC_KEY entry count (excludes the mnemonic entry). */
    private fun serializedKeyCount(chain: DeterministicKeyChain): Int =
        chain.serializeToProtobuf().count { it.type == Protos.Key.Type.DETERMINISTIC_KEY }

    /** Serialized LEAF entries (depth accountPath+1, either path representation). */
    private fun serializedLeafCount(chain: DeterministicKeyChain): Int {
        val leafDepth = chain.accountPath.size + 1
        return chain.serializeToProtobuf().count {
            it.type == Protos.Key.Type.DETERMINISTIC_KEY &&
                (it.deterministicKey.pathCount == leafDepth || it.deterministicKey.extendedPathCount == leafDepth)
        }
    }

    @Test
    fun completedDeferredChain_serializesTheSmallShape_notThe131KeyWindow() {
        val deferred = receivingChain(dashPayPath()) as FriendKeyChain
        // pre-completion = what the file held: a fresh friend chain already
        // carries exactly its two metadata-carrier leaves (the "2 children"
        // of the tester's log arithmetic)
        val baseline = serializedKeyCount(deferred)
        assertEquals(FriendKeyChainLookahead.METADATA_CARRIER_LEAVES, serializedLeafCount(deferred))
        assertTrue(FriendKeyChainLookahead.awaitComplete(60_000L))
        assertTrue("sanity: completion derived the window in memory", deferred.numKeys() > EXPECTED_DERIVED_KEYS)

        // The tester's regression: this used to be baseline + 131 (2.5MB -> 6.5MB
        // across the whole wallet). The unissued window must not round-trip.
        assertEquals(
            "unissued lookahead keys must not be serialized",
            baseline, serializedKeyCount(deferred)
        )
        assertEquals(
            "exactly the two metadata-carrier leaves persist",
            FriendKeyChainLookahead.METADATA_CARRIER_LEAVES, serializedLeafCount(deferred)
        )

        // The eager dashj chain still serializes fatly — the strip is scoped to
        // the deferred subclasses, nothing else's serialization is touched.
        val eager = FriendKeyChain(seed(), null, dashPayPath())
        eager.maybeLookAhead()
        assertTrue(serializedKeyCount(eager) > EXPECTED_DERIVED_KEYS)
    }

    @Test
    fun issuedKeys_alwaysSurviveTheStrip_byteIdentical() {
        val deferred = receivingChain(dashPayPath()) as FriendKeyChain
        assertTrue(FriendKeyChainLookahead.awaitComplete(60_000L))

        val issued = deferred.getKeys(KeyChain.KeyPurpose.RECEIVE_FUNDS, 4)
        assertEquals(4, deferred.issuedExternalKeys)

        val serializedPubKeys = deferred.serializeToProtobuf()
            .filter { it.hasPublicKey() }
            .map { it.publicKey.toByteArray().toList() }
        issued.forEach { key ->
            assertTrue(
                "issued key ${key.path} must persist in the wallet file",
                serializedPubKeys.contains(key.pubKey.toList())
            )
        }
    }

    /**
     * THE load-bearing round trip for the strip: save (stripped) → load →
     * complete, and the watched-key set is byte-identical to the original.
     * This is the production cycle every wallet goes through on each launch,
     * and the proof the strip can never cost a watched address.
     */
    /**
     * A REALISTIC friend path: the user-id components are 256-bit
     * [ExtendedChildNumber]s, which is both what production chains carry and
     * what dashj's `fromProtobuf` keys its friend-chain factory dispatch on.
     */
    private fun extendedDashPayPath(): ImmutableList<ChildNumber> = ImmutableList.of(
        ChildNumber.NINE_HARDENED,
        ChildNumber(5, true),
        DerivationPathFactory.FEATURE_PURPOSE_DASHPAY,
        ChildNumber(0, true),
        ExtendedChildNumber(BigInteger(1, ByteArray(32) { 0x11 })),
        ExtendedChildNumber(BigInteger(1, ByteArray(32) { 0x77 }))
    )

    @Test
    fun strippedFile_reloadsAndReconverges_toTheIdenticalKeySet() {
        val path = extendedDashPayPath()
        val original = receivingChain(path) as FriendKeyChain
        assertTrue(FriendKeyChainLookahead.awaitComplete(60_000L))
        original.getKeys(KeyChain.KeyPurpose.RECEIVE_FUNDS, 3)
        assertEquals(3, original.issuedExternalKeys)

        // Every key (including the full lookahead window) the original owns.
        val originalKeyCount = original.numKeys()
        val eager = FriendKeyChain(seed(), null, path)
        eager.maybeLookAhead()
        eager.getKeys(KeyChain.KeyPurpose.RECEIVE_FUNDS, 3)
        val fullWindow = publicKeys(eager)

        // SAVE: the stripped protobuf...
        val stripped = original.serializeToProtobuf()
        assertTrue(
            "the saved file must hold the small shape",
            stripped.count { it.type == Protos.Key.Type.DETERMINISTIC_KEY } < EXPECTED_DERIVED_KEYS
        )

        // ...LOAD it back through dashj's own deserializer with the deferring
        // factory (exactly the wallet-load path), then complete.
        FriendKeyChainLookahead.reset()
        val reloaded = DeterministicKeyChain.fromProtobuf(stripped, null, FriendKeyChainLookahead.deferringFactory())
            .single() as FriendKeyChain
        assertEquals("the issued count must survive the round trip", 3, reloaded.issuedExternalKeys)
        assertTrue(FriendKeyChainLookahead.awaitComplete(60_000L))

        // The reloaded chain re-derives its window RELATIVE TO the issued
        // count, so it may extend a few keys further than the original — a
        // SUPERSET is funds-safe; a missing key would not be.
        assertTrue(
            "the reloaded chain must own at least every original key",
            reloaded.numKeys() >= originalKeyCount
        )
        fullWindow.forEach {
            assertNotNull(
                "every watched key must be re-derived byte-identically after the round trip",
                reloaded.findKeyFromPubKey(it.toByteArray())
            )
        }
    }

    // ── the pure strip filter ────────────────────────────────────────────

    private fun leafKey(index: Int, depth: Int = 7, extended: Boolean = true, simple: Boolean = true): Protos.Key {
        val dk = Protos.DeterministicKey.newBuilder()
            .setChainCode(com.google.protobuf.ByteString.copyFrom(ByteArray(32)))
        if (extended) {
            repeat(depth - 1) { i ->
                dk.addExtendedPath(
                    Protos.ExtendedChildNumber.newBuilder().setSimple(true).setI(i or -0x80000000).build()
                )
            }
            dk.addExtendedPath(
                if (simple) {
                    Protos.ExtendedChildNumber.newBuilder().setSimple(true).setI(index).build()
                } else {
                    Protos.ExtendedChildNumber.newBuilder().setSimple(false)
                        .setBi(com.google.protobuf.ByteString.copyFrom(ByteArray(32) { 1 })).setSize(32).build()
                }
            )
        } else {
            repeat(depth - 1) { i -> dk.addPath(i or -0x80000000) }
            dk.addPath(index)
        }
        return Protos.Key.newBuilder()
            .setType(Protos.Key.Type.DETERMINISTIC_KEY)
            .setPublicKey(com.google.protobuf.ByteString.copyFrom(ByteArray(33)))
            .setDeterministicKey(dk)
            .build()
    }

    @Test
    fun stripFilter_table() {
        val leafDepth = 7
        // unissued lookahead leaves (>= keepBelow) are strippable, plain or extended path
        assertTrue(FriendKeyChainLookahead.isStrippableLookaheadLeaf(leafKey(5), leafDepth, 2))
        assertTrue(FriendKeyChainLookahead.isStrippableLookaheadLeaf(leafKey(5, extended = false), leafDepth, 2))
        assertTrue(FriendKeyChainLookahead.isStrippableLookaheadLeaf(leafKey(132), leafDepth, 2))
        // the metadata-carrier leaves 0 and 1 are never strippable
        assertFalse(FriendKeyChainLookahead.isStrippableLookaheadLeaf(leafKey(0), leafDepth, 2))
        assertFalse(FriendKeyChainLookahead.isStrippableLookaheadLeaf(leafKey(1), leafDepth, 2))
        // issued leaves are never strippable (issued 5 -> keepBelow 5)
        assertFalse(FriendKeyChainLookahead.isStrippableLookaheadLeaf(leafKey(4), leafDepth, 5))
        assertTrue(FriendKeyChainLookahead.isStrippableLookaheadLeaf(leafKey(5), leafDepth, 5))
        // wrong depth (account/ancestor nodes) — never strippable
        assertFalse(FriendKeyChainLookahead.isStrippableLookaheadLeaf(leafKey(5, depth = 6), leafDepth, 2))
        // hardened child — not a lookahead leaf
        assertFalse(FriendKeyChainLookahead.isStrippableLookaheadLeaf(leafKey(5 or -0x80000000), leafDepth, 2))
        // extended (user-id) child number — never strippable
        assertFalse(FriendKeyChainLookahead.isStrippableLookaheadLeaf(leafKey(5, simple = false), leafDepth, 2))
        // non-deterministic entries (the mnemonic) — never strippable
        val mnemonic = Protos.Key.newBuilder().setType(Protos.Key.Type.DETERMINISTIC_MNEMONIC)
            .setSecretBytes(com.google.protobuf.ByteString.copyFrom(ByteArray(16))).build()
        assertFalse(FriendKeyChainLookahead.isStrippableLookaheadLeaf(mnemonic, leafDepth, 2))
    }

    @Test
    fun stripFilter_keepsOrderAndEverythingElse() {
        val keys = listOf(leafKey(0), leafKey(1), leafKey(2), leafKey(3), leafKey(2, depth = 6))
        val kept = FriendKeyChainLookahead.stripUnissuedLookaheadLeaves(keys, accountPathSize = 6, issuedExternalKeys = 0)
        assertEquals(listOf(keys[0], keys[1], keys[4]), kept)
    }

    @Test
    fun awaitComplete_withNothingQueued_succeedsImmediately() {
        assertTrue(FriendKeyChainLookahead.awaitComplete(1_000L))
        assertEquals(0L, FriendKeyChainLookahead.completionMs())
    }

    @Test
    fun reset_dropsQueuedChains_soAnAbandonedParseCostsNothing() {
        receivingChain(dashPayPath())
        assertEquals(1, FriendKeyChainLookahead.pendingCount())

        FriendKeyChainLookahead.reset()

        assertEquals(0, FriendKeyChainLookahead.pendingCount())
        assertEquals(0, FriendKeyChainLookahead.deferredCount())
        assertTrue(FriendKeyChainLookahead.awaitComplete(1_000L))
    }
}
