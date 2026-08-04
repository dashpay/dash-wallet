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
import org.bitcoinj.core.Utils
import org.bitcoinj.crypto.ChildNumber
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

        assertEquals(
            "the watched-key SET must be identical — only its timing moves",
            eagerKeys, publicKeys(deferred)
        )

        // The funds-detection primitive itself: every address the eager chain
        // would have recognised, the completed deferred chain recognises.
        eager.serializeToProtobuf().filter { it.hasPublicKey() }.forEach {
            assertNotNull(
                "a completed deferred chain must own every eager key",
                deferred.findKeyFromPubKey(it.publicKey.toByteArray())
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
