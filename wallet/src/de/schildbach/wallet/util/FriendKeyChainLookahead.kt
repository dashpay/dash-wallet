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
import java.io.File
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import org.bitcoinj.core.ECKey
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.crypto.HDKeyDerivation
import org.bitcoinj.crypto.KeyCrypter
import org.bitcoinj.crypto.LazyECPoint
import org.bitcoinj.script.Script
import org.bitcoinj.wallet.DefaultKeyChainFactory
import org.bitcoinj.wallet.DerivationPathFactory
import org.bitcoinj.wallet.DeterministicKeyChain
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.FriendKeyChain
import org.bitcoinj.wallet.KeyChainFactory
import org.bitcoinj.wallet.Protos
import org.slf4j.LoggerFactory

/**
 * Moves the DashPay FRIEND key-chain lookahead OFF the wallet-load critical
 * path — the fix for the mainnet launch crash-loop on a wallet with many
 * DashPay contacts — and, since 11.10.61, makes the derived window PERSIST so
 * the work happens once instead of on every launch.
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
 * bulk pre-derivation is queued instead of run. [completeAsync] then provisions
 * exactly the same window on a background pool, and [awaitComplete] is the gate
 * the blockchain service passes through before the wallet is attached to the
 * peer group.
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
 *
 * ## The serialization side: lookahead keys do NOT round-trip (11.10.58)
 *
 * Deferring the derivation exposed the mirror problem: once the completion pass
 * has derived the 100+33 window IN MEMORY, dashj's
 * `DeterministicKeyChain.serializeMyselfToProtobuf` wrote every one of those
 * keys back into the wallet protobuf. Measured on the mainnet tester's wallet:
 * 2,535,811 → 6,489,706 bytes after one completion pass (15,510 receiving +
 * 14,070 sending friend keys), autosaves stretching 34 s → 114 s, and the next
 * launch choking on the sheer deserialization size. So the deferred chains
 * override `serializeMyselfToProtobuf` and STRIP the unissued lookahead leaves
 * ([stripUnissuedLookaheadLeaves]) before the protobuf is written:
 *
 *  * ISSUED leaves always persist — nothing the wallet has handed out or
 *    marked used is touched, so their bytes in the file are unchanged.
 *  * Leaves 0 and 1 always persist even when unissued: dashj's `fromProtobuf`
 *    treats leaf 0 as the carrier of `issued_subkeys`/`lookahead_size` (it
 *    becomes `externalParentKey` on load) and leaf 1 as the internal-counter
 *    carrier — exactly the 2-keys-per-chain shape every pre-11.10.54 wallet
 *    file already had, proving the load path handles it.
 *  * Anything not provably an unissued plain-indexed lookahead leaf is KEPT
 *    (default-keep filter): account/ancestor nodes, hardened children,
 *    extended (user-id) child numbers, other key types.
 *
 * ## Deriving ONCE instead of once per launch (11.10.61)
 *
 * The strip is what made every launch re-derive: the keys are deliberately not
 * in the file, so `completeAsync` rebuilt all of them, every time. The tester's
 * 11.10.60 log shows the cost of that — `215 deferred chains`, `212 of 215
 * chains completed in 158953ms` — 96-167 s of four-thread EC derivation per
 * launch, starving the SDK's filter scan and parking the main thread on the
 * key-chain lock inside `ExternalKeyChain.maybeLookAhead`.
 *
 * Resolution: keep the strip (the wallet protobuf stays at its 2-keys-per-chain
 * baseline) and persist the derived window in a SEPARATE file —
 * [FriendKeyChainLookaheadStore], `<wallet>.friendlookahead`. Unlike the wallet
 * protobuf it is written only when its content actually changes, is never on
 * the parse path, and is not copied by backup/restore. On the next launch the
 * completion pass INSTALLS those keys (a hash-map insert each) instead of
 * deriving them.
 *
 * The store is an accelerator and nothing more:
 *  * it holds public keys and chain codes only — lookahead keys are
 *    `dropPrivateBytes()`d by dashj and never carry private material;
 *  * every entry is bound to its chain's ACCOUNT KEY (public key + chain code),
 *    so an entry can never be applied to the wrong chain;
 *  * before any of an entry's keys are installed, the FIRST and LAST leaf are
 *    RE-DERIVED and compared byte-for-byte; a single mismatch, a gap in the
 *    indexes, a bad checksum, an unreadable or absent file — any of them — and
 *    the chain simply derives, exactly as in 11.10.60;
 *  * installation is index-contiguous from the chain's current `numChildren`,
 *    then the ordinary `maybeLookAhead()` runs anyway to top up whatever the
 *    store did not supply. The post-condition (`numChildren == issued +
 *    lookaheadSize + lookaheadThreshold`) is therefore dashj's own, whatever
 *    the store contained.
 *
 * And when the store DOES miss (first launch after the upgrade), the derivation
 * no longer holds the key-chain lock for the whole 131-key run: it is issued in
 * [DERIVATION_BATCH]-key batches through the protected four-argument
 * `maybeLookAhead`, releasing the lock and yielding between batches, so the main
 * thread can never park behind more than one batch.
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
     * Lookahead keys derived per acquisition of the key-chain lock when the
     * store misses. dashj's own `maybeLookAhead()` derives all 131 under ONE
     * lock acquisition — seconds on a slow device, and the frame in which the
     * tester's main thread parked. Batching bounds that to one batch.
     */
    const val DERIVATION_BATCH = 10

    /**
     * How long the blockchain-service gate waits for completion before giving
     * up and letting sync start anyway. Generous on purpose — it is a
     * last-resort liveness valve, not an expected path (a 150-contact wallet
     * completes in seconds on the pool, and in well under a second once the
     * store is warm). Exceeding it is logged loudly.
     */
    const val DEFAULT_AWAIT_TIMEOUT_MS = 180_000L

    /** Coalescing delay before the store is written back. */
    const val STORE_FLUSH_DELAY_MS = 5_000L

    private val pending = ConcurrentLinkedQueue<DeferredLookahead>()
    private val started = AtomicBoolean(false)
    private val workers = AtomicReference<List<Thread>>(emptyList())
    private val activeWorkers = AtomicInteger()
    private val deferredChains = AtomicInteger()
    private val completedChains = AtomicInteger()
    private val restoredChains = AtomicInteger()
    private val derivedKeys = AtomicInteger()
    private val installedKeys = AtomicInteger()

    /** Where the side store lives, or null when there is none (tests, no wallet file). */
    private val storeFile = AtomicReference<File?>(null)

    /** The store as loaded for THIS launch; null until first read. */
    private val loadedStore = AtomicReference<Map<String, CachedLookaheadChain>?>(null)

    /** Windows that must still be written back. */
    private val stagedStore = ConcurrentHashMap<String, CachedLookaheadChain>()

    /** Per-chain content stamp believed to be on disk already. */
    private val storedSignatures = ConcurrentHashMap<String, String>()

    /** Chains this launch actually provisioned — the live set, for pruning. */
    private val provisionedChainIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val flushScheduled = AtomicBoolean(false)

    /**
     * Set once a completion pass has provisioned EVERY deferred chain, which
     * makes [provisionedChainIds] the authoritative list of the wallet's friend
     * chains: the next write can then drop entries for chains that no longer
     * exist (a removed contact, or a store left behind by a previous wallet).
     */
    private val pruneOnNextFlush = AtomicBoolean(false)

    @Volatile
    private var startedAtMs = 0L

    @Volatile
    private var elapsedMs = -1L

    /** A friend key chain whose bulk lookahead was queued rather than run. */
    private interface DeferredLookahead {
        /** Arm and run the deferred lookahead provisioning. Idempotent. */
        fun completeLookAhead()
    }

    /**
     * The handful of PROTECTED dashj key-chain internals the provisioning needs,
     * bound to one chain instance. Both deferred subclasses build one of these;
     * the algorithm itself lives once, at object level.
     */
    private class ChainOps(
        val lock: ReentrantLock,
        /** `getKeyByPath(getAccountPath())` — the parent `maybeLookAhead()` itself uses. */
        val accountKey: () -> DeterministicKey,
        val numChildren: (DeterministicKey) -> Int,
        val putInHierarchy: (DeterministicKey) -> Unit,
        val importKeys: (List<DeterministicKey>) -> Unit,
        /** `maybeLookAhead(parent, issuedExternalKeys, lookaheadSize, 0)`. */
        val deriveWindow: (DeterministicKey, Int) -> List<DeterministicKey>,
        val issued: () -> Int,
        val lookaheadSize: () -> Int,
        val lookaheadThreshold: () -> Int,
        val bumpLookaheadEpoch: () -> Unit
    )

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

        private fun ops() = ChainOps(
            lock = lock,
            accountKey = { getKeyByPath(getAccountPath()) },
            numChildren = { hierarchy.getNumChildren(it.path) },
            putInHierarchy = { hierarchy.putKey(it) },
            importKeys = { basicKeyChain.importKeys(it) },
            deriveWindow = { parent, size -> maybeLookAhead(parent, getIssuedExternalKeys(), size, 0) },
            issued = { getIssuedExternalKeys() },
            lookaheadSize = { getLookaheadSize() },
            lookaheadThreshold = { getLookaheadThreshold() },
            bumpLookaheadEpoch = { keyLookaheadEpoch++ }
        )

        override fun maybeLookAhead() {
            if (deferred) return
            super.maybeLookAhead()
        }

        override fun completeLookAhead() {
            deferred = false
            provisionLookahead(ops())
            // Belt and braces: dashj's own top-up. A no-op after provisioning,
            // and the full (correct) derivation if provisioning failed.
            maybeLookAhead()
        }

        override fun serializeMyselfToProtobuf(): MutableList<Protos.Key> {
            val accountPathSize = getAccountPath().size
            val partitioned =
                partitionLookaheadLeaves(super.serializeMyselfToProtobuf(), accountPathSize, getIssuedExternalKeys())
            captureStrippedWindow(ops(), accountPathSize, partitioned.removed)
            return partitioned.kept
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

        private fun ops() = ChainOps(
            lock = lock,
            accountKey = { getKeyByPath(getAccountPath()) },
            numChildren = { hierarchy.getNumChildren(it.path) },
            putInHierarchy = { hierarchy.putKey(it) },
            importKeys = { basicKeyChain.importKeys(it) },
            deriveWindow = { parent, size -> maybeLookAhead(parent, getIssuedExternalKeys(), size, 0) },
            issued = { getIssuedExternalKeys() },
            lookaheadSize = { getLookaheadSize() },
            lookaheadThreshold = { getLookaheadThreshold() },
            bumpLookaheadEpoch = { keyLookaheadEpoch++ }
        )

        override fun maybeLookAhead() {
            if (deferred) return
            super.maybeLookAhead()
        }

        override fun completeLookAhead() {
            deferred = false
            provisionLookahead(ops())
            maybeLookAhead()
        }

        override fun serializeMyselfToProtobuf(): MutableList<Protos.Key> {
            val accountPathSize = getAccountPath().size
            val partitioned =
                partitionLookaheadLeaves(super.serializeMyselfToProtobuf(), accountPathSize, getIssuedExternalKeys())
            captureStrippedWindow(ops(), accountPathSize, partitioned.removed)
            return partitioned.kept
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

    /**
     * dashj's `fromProtobuf` reads a friend/external chain's counters off its
     * first leaves: leaf 0 becomes `externalParentKey` and carries
     * `issued_subkeys`/`lookahead_size`/`sigs_required`, leaf 1 becomes
     * `internalParentKey` and carries the internal issued count. They must
     * ALWAYS round-trip — which is exactly the 2-keys-per-chain file shape
     * every pre-lookahead wallet file already had.
     */
    const val METADATA_CARRIER_LEAVES = 2

    private const val HARDENED_BIT = -0x80000000 // 0x80000000 as an Int

    /** The two halves of a friend chain's serialized keys. */
    internal class PartitionedKeys(
        /** What goes into the wallet protobuf. */
        val kept: MutableList<Protos.Key>,
        /** The unissued lookahead leaves that do not, in leaf order. */
        val removed: List<Protos.Key>
    )

    /**
     * Serialization-side companion of the deferred lookahead: drop the
     * UNISSUED lookahead leaves from a friend chain's serialized key list so
     * they never round-trip through the wallet protobuf (they are restored
     * from [FriendKeyChainLookaheadStore], or re-derived byte-identically, on
     * the next load). DEFAULT-KEEP: only entries provably matching the
     * unissued-plain-leaf shape are removed. Pure — unit-tested directly.
     *
     * @param keys the chain's `serializeMyselfToProtobuf()` output
     * @param accountPathSize leaf depth is `accountPathSize + 1`
     * @param issuedExternalKeys leaves `0 until issuedExternalKeys` are issued
     */
    @JvmStatic
    internal fun stripUnissuedLookaheadLeaves(
        keys: List<Protos.Key>,
        accountPathSize: Int,
        issuedExternalKeys: Int
    ): MutableList<Protos.Key> = partitionLookaheadLeaves(keys, accountPathSize, issuedExternalKeys).kept

    /**
     * [stripUnissuedLookaheadLeaves], but keeping the removed leaves too: they
     * are exactly the window [FriendKeyChainLookaheadStore] has to hold so the
     * next launch does not have to derive it again.
     */
    @JvmStatic
    internal fun partitionLookaheadLeaves(
        keys: List<Protos.Key>,
        accountPathSize: Int,
        issuedExternalKeys: Int
    ): PartitionedKeys {
        val leafDepth = accountPathSize + 1
        val keepBelow = maxOf(issuedExternalKeys, METADATA_CARRIER_LEAVES)
        val kept = ArrayList<Protos.Key>(keys.size)
        val removed = ArrayList<Protos.Key>()
        for (key in keys) {
            if (isStrippableLookaheadLeaf(key, leafDepth, keepBelow)) {
                removed.add(key)
            } else {
                kept.add(key)
            }
        }
        return PartitionedKeys(kept, removed)
    }

    /**
     * Whether [key] is PROVABLY an unissued lookahead leaf: a deterministic
     * key at exactly the leaf depth whose final child number is plain
     * (non-hardened, non-extended) with index >= [keepBelow]. Anything
     * ambiguous is NOT strippable.
     */
    @JvmStatic
    internal fun isStrippableLookaheadLeaf(key: Protos.Key, leafDepth: Int, keepBelow: Int): Boolean =
        leafIndexOf(key, leafDepth)?.let { it >= keepBelow } ?: false

    /**
     * The plain, non-hardened leaf index of [key] if it is a deterministic leaf
     * at exactly [leafDepth], else null.
     */
    private fun leafIndexOf(key: Protos.Key, leafDepth: Int): Int? {
        if (key.type != Protos.Key.Type.DETERMINISTIC_KEY || !key.hasDeterministicKey()) return null
        val dk = key.deterministicKey
        val leafIndex: Int
        if (dk.extendedPathCount > 0) {
            // Friend chains serialize the EXTENDED path (their account path
            // contains 256-bit user-id child numbers).
            if (dk.extendedPathCount != leafDepth) return null
            val last = dk.getExtendedPath(dk.extendedPathCount - 1)
            if (!last.simple) return null
            leafIndex = last.i
        } else {
            if (dk.pathCount != leafDepth) return null
            leafIndex = dk.getPath(dk.pathCount - 1)
        }
        if (leafIndex and HARDENED_BIT != 0) return null // hardened — not a lookahead leaf
        return leafIndex
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

    /**
     * Point the side store at the file that accompanies [walletFile]. Call once
     * per wallet load, AFTER [reset]. Passing null disables the store (the
     * chains then derive exactly as they did in 11.10.60).
     */
    @JvmStatic
    fun useStore(walletFile: File?) {
        storeFile.set(walletFile?.let { FriendKeyChainLookaheadStore.storeFileFor(it) })
        loadedStore.set(null)
        stagedStore.clear()
        storedSignatures.clear()
        provisionedChainIds.clear()
        pruneOnNextFlush.set(false)
    }

    /** Chains parsed with a deferred lookahead that have not completed yet. */
    @JvmStatic
    fun pendingCount(): Int = pending.size

    /** Chains deferred by the most recent load (0 once [reset] runs). */
    @JvmStatic
    fun deferredCount(): Int = deferredChains.get()

    /** Chains whose deferred lookahead has finished. */
    @JvmStatic
    fun completedCount(): Int = completedChains.get()

    /** Chains whose window came from the store instead of being derived. */
    @JvmStatic
    fun restoredCount(): Int = restoredChains.get()

    /** Lookahead keys derived (rather than restored) by the completion pass. */
    @JvmStatic
    fun derivedKeyCount(): Int = derivedKeys.get()

    /** Lookahead keys installed from the store by the completion pass. */
    @JvmStatic
    fun installedKeyCount(): Int = installedKeys.get()

    /** Wall time of the completion pass, or -1 while it is still running. */
    @JvmStatic
    fun completionMs(): Long = elapsedMs

    /**
     * Kick the background completion. Idempotent, never throws, returns
     * immediately — call it as soon as the parse returns so the provisioning
     * overlaps the rest of startup.
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
        activeWorkers.set(0)
        deferredChains.set(0)
        completedChains.set(0)
        restoredChains.set(0)
        derivedKeys.set(0)
        installedKeys.set(0)
        startedAtMs = 0L
        elapsedMs = -1L
        storeFile.set(null)
        loadedStore.set(null)
        stagedStore.clear()
        storedSignatures.clear()
        provisionedChainIds.clear()
        pruneOnNextFlush.set(false)
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
        activeWorkers.set(threadCount)
        threads.forEach { it.start() }
        return threads
    }

    private fun drain() {
        try {
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
        } finally {
            // The LAST worker out reports. Doing this on "the queue looks empty"
            // instead used to under-report by up to (threads - 1) chains that
            // were still in flight — the tester's persistent "212 of 215".
            if (activeWorkers.decrementAndGet() == 0) {
                onPassFinished()
            }
        }
    }

    private fun onPassFinished() {
        elapsedMs = System.currentTimeMillis() - startedAtMs
        val deferred = deferredChains.get()
        val completed = completedChains.get()
        log.info(
            "friend key chain lookahead: {} of {} chains completed in {}ms " +
                "({} chains restored from the store: {} keys installed, {} keys derived)",
            completed, deferred, elapsedMs, restoredChains.get(), installedKeys.get(), derivedKeys.get()
        )
        if (completed < deferred) {
            log.error(
                "friend key chain lookahead completed only {} of {} chains — {} chain(s) failed; " +
                    "their windows will be derived on demand",
                completed, deferred, deferred - completed
            )
        }
        // Every chain the wallet holds has now been through provisionLookahead,
        // so anything else in the store belongs to a contact that is gone (or to
        // a previous wallet) and can be dropped on the next write.
        if (deferred > 0 && completed == deferred) {
            pruneOnNextFlush.set(true)
        }
        // Freeing the loaded store here is worth ~3MB on a 200-contact wallet.
        loadedStore.set(emptyMap())
        scheduleStoreFlush()
    }

    // ── provisioning: install from the store, derive whatever is missing ──

    /**
     * Bring one deferred chain's lookahead window up to dashj's post-condition,
     * preferring installation from the side store over derivation.
     */
    private fun provisionLookahead(ops: ChainOps) {
        val parent = try {
            withChainLock(ops) { ops.accountKey() }
        } catch (t: Throwable) {
            log.error("could not resolve a friend chain's account key — deriving", t)
            return
        }

        provisionedChainIds.add(FriendKeyChainLookaheadStore.idOf(parent.pubKey, parent.chainCode))

        val installed = try {
            installFromStore(ops, parent)
        } catch (t: Throwable) {
            log.warn("could not install a friend chain's lookahead from the store — deriving", t)
            emptyList()
        }
        if (installed.isNotEmpty()) {
            restoredChains.incrementAndGet()
            installedKeys.addAndGet(installed.size)
        }

        val derived = deriveRemainingWindow(ops, parent)
        if (derived.isNotEmpty()) {
            derivedKeys.addAndGet(derived.size)
        }

        // The store is only worth rewriting when this launch had to derive.
        if (derived.isNotEmpty()) {
            try {
                stageWindow(parent, installed + derived)
            } catch (t: Throwable) {
                log.warn("could not stage a friend chain's lookahead for the store", t)
            }
        }
    }

    /**
     * Install the store's window for [parent]'s chain. Returns the keys
     * installed — empty whenever anything at all is off, in which case the
     * caller derives instead.
     */
    private fun installFromStore(ops: ChainOps, parent: DeterministicKey): List<DeterministicKey> {
        val cached = loadStore()[FriendKeyChainLookaheadStore.idOf(parent.pubKey, parent.chainCode)]
            ?: return emptyList()
        if (!cached.matchesAccount(parent.pubKey, parent.chainCode)) return emptyList()

        val firstNeeded = withChainLock(ops) { ops.numChildren(parent) }

        // Contiguous run of cached leaves starting exactly at the chain's next child.
        val usable = ArrayList<CachedLookaheadLeaf>(cached.leaves.size)
        var next = firstNeeded
        for (leaf in cached.leaves) {
            if (leaf.index < next) continue
            if (leaf.index != next) break // a gap — stop; derivation covers the rest
            usable.add(leaf)
            next++
        }
        if (usable.isEmpty()) return emptyList()

        // FUNDS GATE for the store: re-derive the ends of the run and require
        // byte equality before a single cached key is trusted. Two EC
        // derivations per chain against the ~131 this replaces.
        if (!verifyLeaf(parent, usable.first()) || !verifyLeaf(parent, usable.last())) {
            log.warn(
                "friend key chain lookahead store entry for {} did not verify — deriving instead",
                parent.pathAsString
            )
            return emptyList()
        }

        ops.lock.lock()
        try {
            var expected = ops.numChildren(parent)
            if (expected != firstNeeded) return emptyList() // raced; derivation covers it
            val keys = ArrayList<DeterministicKey>(usable.size)
            for (leaf in usable) {
                if (leaf.index != expected) break
                keys.add(rebuildLeaf(parent, leaf))
                expected++
            }
            if (keys.isEmpty()) return emptyList()
            keys.forEach(ops.putInHierarchy)
            ops.bumpLookaheadEpoch()
            ops.importKeys(keys)
            return keys
        } finally {
            ops.lock.unlock()
        }
    }

    /**
     * The cached leaf as a [DeterministicKey], built exactly the way dashj's
     * own `DeterministicKey.dropPrivateBytes()` builds a derived lookahead key:
     * same path, same chain code, same compressed point, no private bytes, and
     * the same parent — from which it inherits its creation time.
     */
    private fun rebuildLeaf(parent: DeterministicKey, leaf: CachedLookaheadLeaf): DeterministicKey {
        val path = ImmutableList.builder<ChildNumber>()
            .addAll(parent.path)
            .add(ChildNumber(leaf.index, false))
            .build()
        return DeterministicKey(
            path,
            leaf.chainCode,
            LazyECPoint(ECKey.CURVE.curve, leaf.pubKey),
            null as BigInteger?,
            parent
        )
    }

    /** Re-derive [leaf] from [parent] and compare byte-for-byte. */
    private fun verifyLeaf(parent: DeterministicKey, leaf: CachedLookaheadLeaf): Boolean = try {
        val derived = HDKeyDerivation.deriveChildKey(parent, ChildNumber(leaf.index, false))
        derived.pubKey.contentEquals(leaf.pubKey) && derived.chainCode.contentEquals(leaf.chainCode)
    } catch (t: Throwable) {
        false
    }

    /**
     * Derive whatever the store did not supply, in [DERIVATION_BATCH]-key
     * batches so the key-chain lock is never held for a long run. The
     * post-condition is dashj's own: `numChildren == issued + lookaheadSize +
     * lookaheadThreshold`, reached only if dashj's own `needed > threshold`
     * test says the window is worth extending at all.
     */
    private fun deriveRemainingWindow(ops: ChainOps, parent: DeterministicKey): List<DeterministicKey> {
        val target: Int
        ops.lock.lock()
        try {
            val numChildren = ops.numChildren(parent)
            val threshold = ops.lookaheadThreshold()
            val needed = ops.issued() + ops.lookaheadSize() + threshold - numChildren
            if (needed <= threshold) return emptyList() // exactly dashj's own bail-out
            target = numChildren + needed
        } finally {
            ops.lock.unlock()
        }

        val all = ArrayList<DeterministicKey>()
        while (true) {
            val batch = deriveOneBatch(ops, parent, target) ?: break
            all.addAll(batch)
            // Let the foreground (and the SDK's filter scan) in between batches.
            Thread.yield()
        }
        return all
    }

    /** One batch, under one lock acquisition. Null once the window is full. */
    private fun deriveOneBatch(ops: ChainOps, parent: DeterministicKey, target: Int): List<DeterministicKey>? {
        ops.lock.lock()
        try {
            val numChildren = ops.numChildren(parent)
            if (numChildren >= target) return null
            val step = minOf(target - numChildren, DERIVATION_BATCH)
            // dashj computes needed = issued + lookaheadSize + threshold - numChildren.
            // With threshold 0, this lookaheadSize argument makes needed == step.
            val batch = ops.deriveWindow(parent, numChildren + step - ops.issued())
            if (batch.isEmpty()) return null
            ops.bumpLookaheadEpoch()
            ops.importKeys(batch)
            return batch
        } finally {
            ops.lock.unlock()
        }
    }

    // ── the side store ────────────────────────────────────────────────────

    private fun loadStore(): Map<String, CachedLookaheadChain> {
        loadedStore.get()?.let { return it }
        synchronized(this) {
            loadedStore.get()?.let { return it }
            val file = storeFile.get()
            val loaded = if (file == null) {
                emptyMap()
            } else {
                val chains = FriendKeyChainLookaheadStore.read(file)
                if (chains.isNotEmpty()) {
                    log.info(
                        "friend key chain lookahead store: {} chains / {} keys from {}",
                        chains.size, chains.sumOf { it.leaves.size }, file.name
                    )
                }
                chains.associateBy { it.id }
            }
            loaded.forEach { (id, chain) -> storedSignatures[id] = chain.signature }
            loadedStore.set(loaded)
            return loaded
        }
    }

    /**
     * Remember [leaves] as this chain's window, to be written back.
     *
     * Called only when the launch had to DERIVE something, which means whatever
     * the store held for this chain was absent, stale, gapped or did not
     * verify — so it is rewritten unconditionally. (The content-stamp
     * short-circuit belongs on the autosave path, [captureStrippedWindow],
     * where it saves work on every save; here it would let a rejected entry
     * survive forever, since the stamp cannot see WHICH keys an entry holds.)
     */
    private fun stageWindow(parent: DeterministicKey, leaves: List<DeterministicKey>) {
        if (storeFile.get() == null || leaves.isEmpty()) return
        val cached = leaves
            .map { CachedLookaheadLeaf(it.childNumber.num(), it.pubKey, it.chainCode) }
            .sortedBy { it.index }
        val id = FriendKeyChainLookaheadStore.idOf(parent.pubKey, parent.chainCode)
        stagedStore[id] = CachedLookaheadChain(parent.pubKey, parent.chainCode, cached)
        storedSignatures.remove(id)
    }

    /**
     * Serialization-time capture: whatever the strip removed IS this chain's
     * unissued window, so it is exactly what the store must hold. Called on the
     * autosave thread under the chain lock, and therefore does no I/O — and no
     * work at all when the window is unchanged, which is the steady state.
     */
    private fun captureStrippedWindow(ops: ChainOps, accountPathSize: Int, removed: List<Protos.Key>) {
        if (storeFile.get() == null || removed.isEmpty()) return
        try {
            val parent = ops.accountKey()
            val id = FriendKeyChainLookaheadStore.idOf(parent.pubKey, parent.chainCode)
            val leafDepth = accountPathSize + 1
            val leaves = ArrayList<CachedLookaheadLeaf>(removed.size)
            for (key in removed) {
                val index = leafIndexOf(key, leafDepth) ?: return
                leaves.add(
                    CachedLookaheadLeaf(
                        index,
                        key.publicKey.toByteArray(),
                        key.deterministicKey.chainCode.toByteArray()
                    )
                )
            }
            leaves.sortBy { it.index }
            if (storedSignatures[id] == CachedLookaheadChain.signatureOf(leaves)) return
            stagedStore[id] = CachedLookaheadChain(parent.pubKey, parent.chainCode, leaves)
            scheduleStoreFlush()
        } catch (t: Throwable) {
            // The store is an accelerator; never let it break a wallet save.
            log.warn("could not capture a friend chain's lookahead window for the store", t)
        }
    }

    private inline fun <T> withChainLock(ops: ChainOps, body: () -> T): T {
        ops.lock.lock()
        try {
            return body()
        } finally {
            ops.lock.unlock()
        }
    }

    /** Coalesced, off-thread write-back. At most one writer is ever pending. */
    private fun scheduleStoreFlush(delayMs: Long = STORE_FLUSH_DELAY_MS) {
        if (storeFile.get() == null) return
        if (stagedStore.isEmpty() && !pruneOnNextFlush.get()) return
        if (!flushScheduled.compareAndSet(false, true)) return
        try {
            Thread({
                try {
                    Thread.sleep(delayMs)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                flushScheduled.set(false)
                flushStoreNow()
            }, "friend-lookahead-store").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }.start()
        } catch (t: Throwable) {
            flushScheduled.set(false)
            log.warn("could not schedule the friend key chain lookahead store write", t)
        }
    }

    /**
     * Merge everything staged over what is already on disk and write it back.
     * Safe to call from anywhere; never throws.
     */
    @JvmStatic
    internal fun flushStoreNow() {
        val file = storeFile.get() ?: return
        val prune = pruneOnNextFlush.compareAndSet(true, false)
        if (stagedStore.isEmpty() && !prune) return
        try {
            val snapshot = HashMap(stagedStore)
            snapshot.keys.forEach(stagedStore::remove)

            val merged = LinkedHashMap<String, CachedLookaheadChain>()
            FriendKeyChainLookaheadStore.read(file).forEach { merged[it.id] = it }
            val stale = if (prune) merged.keys.count { it !in provisionedChainIds } else 0
            if (snapshot.isEmpty() && stale == 0) return // nothing to add, nothing to drop
            if (prune) {
                merged.keys.retainAll(provisionedChainIds)
            }
            merged.putAll(snapshot)
            if (merged.isEmpty() && !file.exists()) return

            if (FriendKeyChainLookaheadStore.write(file, merged.values)) {
                snapshot.forEach { (id, chain) -> storedSignatures[id] = chain.signature }
                log.info(
                    "friend key chain lookahead store written: {} chains / {} keys ({} updated, {} stale dropped)",
                    merged.size, merged.values.sumOf { it.leaves.size }, snapshot.size, stale
                )
            } else {
                // Keep the work for a later attempt, without clobbering newer staging.
                snapshot.forEach { (id, chain) -> stagedStore.putIfAbsent(id, chain) }
            }
        } catch (t: Throwable) {
            log.warn("could not write the friend key chain lookahead store", t)
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
