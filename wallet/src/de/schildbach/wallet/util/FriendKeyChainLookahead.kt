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
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.crypto.KeyCrypter
import org.bitcoinj.script.Script
import org.bitcoinj.wallet.DefaultKeyChainFactory
import org.bitcoinj.wallet.DerivationPathFactory
import org.bitcoinj.wallet.DeterministicKeyChain
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.FriendKeyChain
import org.bitcoinj.wallet.KeyChainFactory
import org.bitcoinj.wallet.Protos
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Moves the DashPay FRIEND key-chain lookahead OFF the wallet-load critical
 * path — the fix for the mainnet launch crash-loop on a wallet with many
 * DashPay contacts.
 *
 * ## The failure this fixes (measured from a tester's 11.10.54 log)
 *
 * `WalletProtobufSerializer.readWallet` deserializes the two DashPay friend
 * key-chain groups (`keysForFriends` / `keysFromFriends`). For every contact
 * chain whose lookahead buffer is not already in the file,
 * `DeterministicKeyChain.fromProtobuf` calls `setLookaheadSize(...)` and then
 * `maybeLookAhead()`, and `ExternalKeyChain.maybeLookAhead` derives
 *
 *     issued(0) + lookaheadSize(100) + lookaheadThreshold(33) - numChildren(2)
 *     = 131 keys per contact chain
 *
 * at ~1.18 s per chain on the tester's device. His launch reached
 * `WALLET_LOAD_BEGIN` and never reached `WALLET_PROTOBUF_PARSED`: at least 67
 * chains × 131 keys (≈8,800 EC derivations) on the two `wallet-deserializer`
 * threads — minutes of blocking work inside `Application.onCreate`, with no
 * window on screen. Every launch died the same way. The wallet file itself is
 * 2.5 MB — the size guard correctly never fired; SIZE was never the problem.
 *
 * ## What this class changes — and, precisely, what it does NOT
 *
 * A [KeyChainFactory] ([deferringFactory]) hands the deserializer FriendKeyChain
 * subclasses whose NO-ARG `maybeLookAhead()` is inert until armed. The parse
 * therefore builds the chains, adopts their persisted keys and returns — the
 * bulk pre-derivation is queued instead of run. [completeAsync] then performs
 * exactly the same `maybeLookAhead()` calls on a background pool, and
 * [awaitComplete] is the gate the blockchain service passes through before the
 * wallet is attached to the peer group.
 *
 * FUNDS SAFETY — the set of derived/watched keys is UNCHANGED:
 *  * lookaheadSize and lookaheadThreshold are not touched. The completed chains
 *    hold byte-identical keys (same seed/xpub, same paths, same 100+33 window)
 *    to the ones today's synchronous parse produces. Only the WHEN moves.
 *  * On-demand key issuance is untouched: `FriendKeyChain.getKeys(purpose, n)`
 *    derives the requested keys straight from the chain's `hierarchy` and calls
 *    the PROTECTED four-argument `maybeLookAhead(parent, issued, 0, 0)` — never
 *    the no-arg method overridden here. A key handed out during the deferral
 *    window is therefore byte-identical to one handed out today.
 *  * `FriendKeyChainGroup.createCurrentContactKeysMap` resolves the current
 *    contact key through `getKeyByPath(accountPath + issuedExternalKeys)`,
 *    which derives from the hierarchy on demand and never reads the lookahead
 *    buffer — so deserialization is complete and correct without it.
 *  * Nothing observes the wallet for FUNDS before the gate. The two other
 *    virtual `maybeLookAhead()` callers — `getFilter()` (bloom filter build)
 *    and `markKeyAsUsed()` (a matched payment) — are both downstream of
 *    `PeerGroup.addWallet(wallet)`, and `BlockchainServiceImpl` awaits
 *    completion immediately before that call. No block and no transaction is
 *    ever matched against a partially-provisioned chain.
 *  * The persisted file cannot get worse: a launch that dies before completion
 *    leaves the same 2-keys-per-chain protobuf it started from.
 *
 * Deliberately NOT done: shrinking the 100+33 lookahead. On a RECEIVING friend
 * chain that window IS the gap limit for payments a contact sends you while you
 * are offline; narrowing it would narrow received-funds detection, which is not
 * a trade this fix is allowed to make.
 */
object FriendKeyChainLookahead {
    private val log = LoggerFactory.getLogger(FriendKeyChainLookahead::class.java)

    /**
     * Ceiling on the completion pool. The work is pure EC derivation (CPU
     * bound) running while the UI comes up, so it must leave cores for the
     * foreground: `cores - 1`, clamped here.
     */
    const val MAX_PARALLELISM = 4

    /**
     * How long the blockchain-service gate waits for completion before giving
     * up and letting sync start anyway. Generous on purpose — it is a
     * last-resort liveness valve, not an expected path (a 150-contact wallet
     * completes in seconds on the pool). Exceeding it is logged loudly.
     */
    const val DEFAULT_AWAIT_TIMEOUT_MS = 180_000L

    private val pending = ConcurrentLinkedQueue<DeferredLookahead>()
    private val started = AtomicBoolean(false)
    private val workers = AtomicReference<List<Thread>>(emptyList())
    private val deferredChains = AtomicInteger()
    private val completedChains = AtomicInteger()

    @Volatile
    private var startedAtMs = 0L

    @Volatile
    private var elapsedMs = -1L

    /** A friend key chain whose bulk lookahead was queued rather than run. */
    private interface DeferredLookahead {
        /** Arm and run the deferred `maybeLookAhead()`. Idempotent. */
        fun completeLookAhead()
    }

    /**
     * The RECEIVING chain (derived from OUR seed at
     * `m/9'/5'/15'/account'/theirUserId/ourUserId`): the addresses a contact
     * pays us at. dashj builds it through `makeSpendingFriendKeyChain`.
     */
    private class DeferredReceivingFriendKeyChain(
        seed: DeterministicSeed,
        crypter: KeyCrypter?,
        accountPath: ImmutableList<ChildNumber>
    ) : FriendKeyChain(seed, crypter, accountPath), DeferredLookahead {
        @Volatile
        private var deferred = true

        override fun maybeLookAhead() {
            if (deferred) return
            super.maybeLookAhead()
        }

        override fun completeLookAhead() {
            deferred = false
            maybeLookAhead()
        }
    }

    /**
     * The SENDING chain (watching the contact's xpub): the addresses WE derive
     * to pay a contact. dashj builds it through `makeWatchingFriendKeyChain`.
     */
    private class DeferredSendingFriendKeyChain(
        accountKey: DeterministicKey
    ) : FriendKeyChain(accountKey), DeferredLookahead {
        @Volatile
        private var deferred = true

        override fun maybeLookAhead() {
            if (deferred) return
            super.maybeLookAhead()
        }

        override fun completeLookAhead() {
            deferred = false
            maybeLookAhead()
        }
    }

    /**
     * Mirrors `DefaultKeyChainFactory`'s own DashPay test (`path[0] == 9H &&
     * path[2] == FEATURE_PURPOSE_DASHPAY`) so that exactly the chains dashj
     * would have built as [FriendKeyChain]s — and no others — are deferred.
     * The extra length check only makes this stricter than dashj's (which
     * would throw on a short path); anything that fails it is delegated
     * untouched.
     */
    @JvmStatic
    internal fun isDashPayFriendPath(accountPath: List<ChildNumber>?): Boolean {
        if (accountPath == null || accountPath.size < 3) return false
        return accountPath[0] == ChildNumber.NINE_HARDENED &&
            accountPath[2] == DerivationPathFactory.FEATURE_PURPOSE_DASHPAY
    }

    /** Threads to complete [chains] deferred chains on a [cores]-core device. */
    @JvmStatic
    internal fun parallelism(cores: Int, chains: Int): Int {
        if (chains <= 0) return 0
        val usable = (cores - 1).coerceAtLeast(1)
        return minOf(usable, MAX_PARALLELISM, chains)
    }

    /**
     * A [KeyChainFactory] for `WalletProtobufSerializer.setKeyChainFactory`
     * that defers DashPay friend-chain lookahead and delegates everything else
     * to `DefaultKeyChainFactory` verbatim.
     */
    @JvmStatic
    fun deferringFactory(): KeyChainFactory = DeferringKeyChainFactory()

    /** Chains parsed with a deferred lookahead that have not completed yet. */
    @JvmStatic
    fun pendingCount(): Int = pending.size

    /** Chains deferred by the most recent load (0 once [reset] runs). */
    @JvmStatic
    fun deferredCount(): Int = deferredChains.get()

    /** Chains whose deferred lookahead has finished. */
    @JvmStatic
    fun completedCount(): Int = completedChains.get()

    /** Wall time of the completion pass, or -1 while it is still running. */
    @JvmStatic
    fun completionMs(): Long = elapsedMs

    /**
     * Kick the background completion. Idempotent, never throws, returns
     * immediately — call it as soon as the parse returns so the derivations
     * overlap the rest of startup.
     */
    @JvmStatic
    fun completeAsync() {
        try {
            start()
        } catch (t: Throwable) {
            log.error("failed to start friend key chain lookahead completion", t)
        }
    }

    /**
     * Block until every deferred chain is complete (or [timeoutMs] elapses).
     * Starts the pass if [completeAsync] never ran. MUST be passed before the
     * wallet is attached to the peer group — see the class KDoc.
     *
     * @return true when the deferred work is fully done.
     */
    @JvmStatic
    @JvmOverloads
    fun awaitComplete(timeoutMs: Long = DEFAULT_AWAIT_TIMEOUT_MS): Boolean {
        val threads = try {
            start()
        } catch (t: Throwable) {
            log.error("failed to start friend key chain lookahead completion", t)
            return false
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        for (thread in threads) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            try {
                thread.join(remaining)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        val done = pending.isEmpty() && threads.none { it.isAlive }
        if (!done) {
            log.error(
                "friend key chain lookahead did NOT finish within {}ms ({} of {} chains done, {} pending) — " +
                    "proceeding so sync is not blocked",
                timeoutMs, completedChains.get(), deferredChains.get(), pending.size
            )
        }
        return done
    }

    /** Drop all state — for a second wallet load in one process, and tests. */
    @JvmStatic
    fun reset() {
        pending.clear()
        workers.set(emptyList())
        started.set(false)
        deferredChains.set(0)
        completedChains.set(0)
        startedAtMs = 0L
        elapsedMs = -1L
    }

    private fun start(): List<Thread> {
        if (!started.compareAndSet(false, true)) return workers.get()
        startedAtMs = System.currentTimeMillis()
        val total = pending.size
        if (total == 0) {
            elapsedMs = 0L
            workers.set(emptyList())
            return emptyList()
        }
        val threadCount = parallelism(Runtime.getRuntime().availableProcessors(), total)
        log.info(
            "friend key chain lookahead: completing {} deferred DashPay chains on {} thread(s)",
            total, threadCount
        )
        val threads = (0 until threadCount).map { index ->
            Thread({ drain() }, "friend-lookahead-$index").apply {
                isDaemon = true
                priority = (Thread.NORM_PRIORITY - 1).coerceAtLeast(Thread.MIN_PRIORITY)
            }
        }
        workers.set(threads)
        // The last worker to finish stamps the elapsed time.
        threads.forEach { it.start() }
        return threads
    }

    private fun drain() {
        while (true) {
            val chain = pending.poll() ?: break
            try {
                chain.completeLookAhead()
                completedChains.incrementAndGet()
            } catch (t: Throwable) {
                // One bad chain must not strand the rest; the chain keeps the
                // keys it already had and its next getKeys() derives normally.
                log.error("deferred lookahead failed for one friend key chain — continuing", t)
            }
        }
        if (pending.isEmpty() && elapsedMs < 0) {
            elapsedMs = System.currentTimeMillis() - startedAtMs
            log.info(
                "friend key chain lookahead: {} of {} chains completed in {}ms",
                completedChains.get(), deferredChains.get(), elapsedMs
            )
        }
    }

    private class DeferringKeyChainFactory : KeyChainFactory {
        private val delegate = DefaultKeyChainFactory()

        override fun makeKeyChain(
            key: Protos.Key,
            firstSubKey: Protos.Key?,
            seed: DeterministicSeed,
            crypter: KeyCrypter?,
            isMarried: Boolean,
            outputScriptType: Script.ScriptType,
            accountPath: ImmutableList<ChildNumber>
        ): DeterministicKeyChain =
            delegate.makeKeyChain(key, firstSubKey, seed, crypter, isMarried, outputScriptType, accountPath)

        override fun makeWatchingKeyChain(
            key: Protos.Key,
            firstSubKey: Protos.Key?,
            accountKey: DeterministicKey,
            isFollowingKey: Boolean,
            isMarried: Boolean,
            outputScriptType: Script.ScriptType
        ): DeterministicKeyChain =
            delegate.makeWatchingKeyChain(key, firstSubKey, accountKey, isFollowingKey, isMarried, outputScriptType)

        override fun makeSpendingKeyChain(
            key: Protos.Key,
            firstSubKey: Protos.Key?,
            accountKey: DeterministicKey,
            isMarried: Boolean,
            outputScriptType: Script.ScriptType
        ): DeterministicKeyChain =
            delegate.makeSpendingKeyChain(key, firstSubKey, accountKey, isMarried, outputScriptType)

        override fun makeSpendingFriendKeyChain(
            key: Protos.Key,
            firstSubKey: Protos.Key?,
            seed: DeterministicSeed,
            crypter: KeyCrypter?,
            isMarried: Boolean,
            accountPath: ImmutableList<ChildNumber>
        ): DeterministicKeyChain {
            if (!isMarried && isDashPayFriendPath(accountPath)) {
                val chain = DeferredReceivingFriendKeyChain(seed, crypter, accountPath)
                pending.add(chain)
                deferredChains.incrementAndGet()
                return chain
            }
            return delegate.makeSpendingFriendKeyChain(key, firstSubKey, seed, crypter, isMarried, accountPath)
        }

        override fun makeWatchingFriendKeyChain(
            accountKey: DeterministicKey,
            accountPath: ImmutableList<ChildNumber>
        ): DeterministicKeyChain {
            if (isDashPayFriendPath(accountPath)) {
                val chain = DeferredSendingFriendKeyChain(accountKey)
                pending.add(chain)
                deferredChains.incrementAndGet()
                return chain
            }
            return delegate.makeWatchingFriendKeyChain(accountKey, accountPath)
        }
    }
}
