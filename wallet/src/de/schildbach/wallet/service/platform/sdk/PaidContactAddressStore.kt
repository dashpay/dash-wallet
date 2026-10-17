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

import de.schildbach.wallet.util.AtomicFileWriter
import de.schildbach.wallet.util.WalletSaveTracker
import org.bitcoinj.wallet.FriendChainAccess
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Contact payment addresses (base58) that are paid, or claimed by a payment
 * that is being sent, written to disk BEFORE the payment is sent, so
 * [ContactPaymentAddressProvider] never hands one out twice — across
 * concurrent sends, restarts, process deaths at any step, and wallet
 * replacement (wipe, restore, recovery from the key backup).
 *
 * One entry per address. It is created by [claim], atomically with the check
 * that no entry exists, and is on disk before [claim] reports
 * [ClaimResult.CLAIMED]: a second send of the same address (another send
 * screen) is refused, and a process that dies mid-send comes back with the
 * address excluded. A claim fails closed ([ClaimResult.STORE_UNAVAILABLE]) when
 * the file could not be read (its exclusions are unknown) or the claim could
 * not be written (it would not survive a process death): nothing may be sent
 * then. A send that fails provably before anything was broadcast gives the
 * address back ([release]); every other outcome keeps it. Bookkeeping after
 * the claim ([sent], settlement, [release]) is fail-soft: a failed write keeps
 * the state in memory and is retried by the next change — the claim it builds
 * on is already on disk.
 *
 * An entry is kept until the stack that could serve the address again has
 * recorded the payment durably: dashj.
 * [FriendChainAccess.markSendingAddressUsed] advances the contact's sending
 * chain in memory and only schedules a wallet save (up to a minute on a large
 * wallet), so a process that dies first comes back at the paid key. An entry is
 * SETTLED once dashj has been advanced past it ([dashjMarked]) and a wallet
 * save that started after that has completed ([WalletSaveTracker]) — dashj's
 * persisted chain is then past it.
 *
 * Nothing else settles an entry — in particular not the SDK's Room mirror
 * flagging the address used while dashj has no chain for the contact (PR #1608
 * review, round 5): dashj builds a missing sending chain later
 * (PlatformSyncService's database integrity check retries it), and a new chain
 * starts at index 0. An entry is therefore kept, and its dashj mark re-applied
 * on every [ContactPaymentAddressProvider.nextAddress] for that contact
 * ([awaitingDashj]), until dashj has a chain, has been advanced past it and has
 * saved — so the chain dashj builds later is advanced past every address paid
 * before it existed.
 *
 * Settlement is about ONE dashj wallet file: when the app's wallet is replaced
 * or removed (reported through [WalletSaveTracker.walletChanged]; the app's
 * store is created for it if need be, see [appWalletChanged]) every entry
 * becomes unsettled again — before anything is expired — and is re-applied to
 * the new wallet's chains and settled by its saves like a new one.
 *
 * ## The channel frontier: what survives an expired entry (PR #1608 review, round 6)
 *
 * A settled entry is redundant only while the dashj wallet that settled it is
 * the one in use. A later wallet — an older key backup, a restore — can have
 * that contact's sending chain at an earlier key, so expiring an entry must not
 * forget what it protected. Each entry therefore also records the address's
 * derivation INDEX on its channel (contact + accountReference, i.e. the
 * contact's xpub for us) when it is known, and an expiring entry is FOLDED into
 * that channel's frontier: one line per channel holding the highest paid index
 * and its address, never expired, independent of any wallet. Every index at or
 * below it has been paid on that channel (payments take the lowest index above
 * what was paid; skipping an unpaid one is harmless). The provider uses it on
 * every lookup ([foldedFrontier], [paidFrontier]): the SDK pick is taken above
 * it, and a dashj chain at or below it is advanced past it (dashj derives
 * the key at the frontier's index, checks it is the frontier's address and
 * issues keys up to it, [FriendChainAccess.advanceSendingChainPast]) or not
 * used. An entry whose index is not known (rare: dashj's cursor could not be
 * read when it served the address) is never expired — it cannot be folded.
 *
 * Why the file stays small: an entry settles with the first wallet save after
 * its payment (normally within a minute while the app is open) and is folded
 * after [RETENTION_MS] or beyond [MAX_ENTRIES]; the frontier is one line per
 * channel. What stays unsettled is a payment dashj cannot place — no chain yet
 * for the contact, the key not on its chain, or no dashj identity — at most one
 * line per such payment.
 *
 * Scoping: a contact payment address is derived from the xpub that contact
 * gave THIS identity for ONE contact request (accountReference, which is
 * derived from that xpub), so an address and a channel name its wallet and
 * contact. The contact and accountReference are stored to re-apply the dashj
 * mark and to key the frontier.
 *
 * ## What the store cannot know: payments made before it existed (PR #1608 review, round 9)
 *
 * The file is in the app's private files, which Android backup and device
 * transfer exclude (data_extraction_rules.xml). After the device is lost or
 * the app's data is cleared, the wallet comes back from its recovery phrase
 * (or a key backup) WITHOUT this file — and without any record of the contact
 * payments made before. The recovery source of truth for those is the
 * blockchain: once the SDK has processed the restored wallet's transactions
 * into its store through the chain tip ([ContactChainScanState.processedThroughTip]),
 * every earlier payment is in its transaction store
 * ([ContactPaymentChainView.paysAddress]). The store therefore records
 * whether the paid-address history is KNOWN for a wallet ([paidHistoryKnown]):
 * the id of the SDK wallet whose store has been processed through the tip at
 * least once since this store began covering it ([markPaidHistoryKnown]).
 * What a lookup finds paid there is recorded like a sent payment
 * ([recordPaidOnChain]). It is unknown
 * when there is no file (new install, cleared data, new device), when the file
 * predates the marker, after every wallet change ([onWalletChanged]), once the
 * SDK's transaction store is deleted to be rebuilt ([forgetPaidHistory]), and
 * for any other SDK wallet. While it is unknown, [ContactPaymentAddressProvider]
 * hands out no post-cutover contact address at all.
 *
 * File format: optionally `history <sdkWalletId>` ([paidHistoryKnown]), then
 * one line per channel frontier,
 * `frontier <contactUserId> <accountReference> <index> <base58>`, then one line
 * per address, `<paidAtMillis> <base58> <pending|settled> [<contactUserId>
 * <accountReference> [<index>]]`, replaced atomically (write and fsync a temp
 * file, rename). A line of just `<paidAtMillis> <base58>` (earlier builds) reads
 * as pending with no contact. [file] null keeps the set in memory only (claims
 * then succeed in memory). A failed read leaves the file untouched and refuses
 * claims for the rest of the process.
 */
class PaidContactAddressStore(
    private val file: File?,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val walletSaves: WalletSaveTracker = WalletSaveTracker()
) {
    /** What [claim] decided. */
    enum class ClaimResult {
        /** Recorded and on disk: the send may run. */
        CLAIMED,

        /**
         * Already paid or claimed by another send, or not provably above the
         * channel's paid frontier (index unknown or at/below it): nothing may
         * be sent to it; resolve the contact's address again.
         */
        ALREADY_PAID,

        /**
         * The store could not be read (its exclusions are unknown) or the claim
         * could not be written: nothing may be sent. Resolving the address
         * again does not help.
         */
        STORE_UNAVAILABLE
    }

    /** The highest paid derivation [index] on a channel and its [address]. */
    data class Frontier(val index: Int, val address: String)

    private data class Channel(val contactUserId: String, val accountReference: Int)

    private val lock = Any()

    private class Entry(
        val paidAt: Long,
        val contactUserId: String?,
        val accountReference: Int?,
        /** The address's derivation index on its channel; null when unknown. */
        val index: Int?,
        var settled: Boolean
    ) {
        val channel: Channel?
            get() = if (contactUserId != null && accountReference != null) {
                Channel(contactUserId, accountReference)
            } else {
                null
            }

        /** Whether expiring it keeps its protection: it has a channel and an index. */
        val foldable: Boolean
            get() = channel != null && index != null

        /**
         * [WalletSaveTracker.lastStarted] read after dashj was advanced past the
         * address in THIS process; a completed save numbered higher settles it.
         */
        var dashjAdvancedAfterSave: Long? = null

        /**
         * dashj reported, in this process, that it cannot place the address
         * (key not on its chain, no dashj identity): not re-applied again until
         * a restart or a wallet change. Not set for "no chain" — a chain can
         * appear at any time and is checked on every lookup.
         */
        var dashjCannotPlace: Boolean = false

        /** Claimed by a send in THIS process that has not finished yet. */
        var inFlight: Boolean = false
    }

    /** address → entry, oldest first; loaded on first use. */
    private var entries: LinkedHashMap<String, Entry>? = null

    /** channel → the highest index folded from expired entries; loaded with [entries]. */
    private val folded = HashMap<Channel, Frontier>()

    /**
     * The SDK wallet whose paid-address history is known ([paidHistoryKnown]);
     * null when none is. Loaded with [entries].
     */
    private var historyWalletId: String? = null

    /**
     * Bumped whenever the marker is dropped ([forgetPaidHistory],
     * [onWalletChanged]): evidence read before a drop is about a transaction
     * store that has since been deleted or replaced, so it must not record
     * the marker again ([markPaidHistoryKnown]). In memory only.
     */
    private var historyGeneration = 0L

    /**
     * Deletions of an SDK transaction store in progress
     * ([beginHistoryInvalidation] .. [endHistoryInvalidation]): while any is,
     * [markPaidHistoryKnown] records nothing. The generation alone only
     * rejects evidence read before the first drop; a lookup that starts after
     * it, while the old store is still there to read, would pass every check
     * and record the marker again (PR #1608 review, round 14). In memory only:
     * a process that dies mid-deletion leaves no marker it could trust.
     */
    private var invalidationsInProgress = 0

    /** The last write failed: the next change, or [sent], writes again. */
    private var unwritten = false

    /**
     * The file exists but could not be read: this process keeps its entries
     * in memory only, never overwrites the file (the exclusions it holds are
     * there again for the next process) and refuses every [claim].
     */
    private var readFailed = false

    init {
        walletSaves.addListener(::onWalletSaved)
        walletSaves.addWalletChangedListener(::onWalletChanged)
    }

    /**
     * Every address recorded paid or claimed by a send, including each
     * channel's frontier address.
     */
    fun paid(): Set<String> = synchronized(lock) {
        loaded().keys + folded.values.map { it.address }
    }

    /** Whether [address] is recorded paid or claimed by a send (see [paid]). */
    fun contains(address: String): Boolean = synchronized(lock) {
        address in loaded() || folded.values.any { it.address == address }
    }

    /**
     * Claims [address], index [index] on [target]'s channel when known, for a
     * payment about to be sent: if nothing is recorded for it, records it
     * (unsettled, in flight) and writes the store; [ClaimResult.CLAIMED] only
     * once the claim is on disk (or the store is memory-only). Check and record
     * are one atomic step, so of two concurrent claims of one address exactly
     * one wins. On a channel with a folded frontier ([foldedFrontier]) the
     * claim needs an [index] above it: at or below it, or null (unknown — the
     * frontier no longer lists the earlier paid addresses), it is
     * [ClaimResult.ALREADY_PAID]. Nothing changes unless it returns
     * [ClaimResult.CLAIMED].
     */
    fun claim(address: String, target: ContactPaymentTarget, index: Int? = null): ClaimResult =
        synchronized(lock) {
            val map = loaded()
            if (readFailed) {
                log.warn("contact payment refused: {} could not be read, its paid addresses are unknown", file)
                return ClaimResult.STORE_UNAVAILABLE
            }
            if (address in map || folded.values.any { it.address == address }) {
                return ClaimResult.ALREADY_PAID
            }
            val channel = Channel(target.contactUserId, target.accountReference)
            val frontier = folded[channel]
            // The frontier keeps only the channel's highest paid address: an
            // address whose index is unknown cannot be shown to lie above it,
            // so it may be an earlier paid one — refused like a paid address.
            if (frontier != null && (index == null || index <= frontier.index)) {
                log.warn(
                    "contact payment refused: index {} is not provably above the channel's paid frontier {}",
                    index ?: "unknown", frontier.index
                )
                return ClaimResult.ALREADY_PAID
            }
            prune(map)
            map[address] = Entry(nowMs(), target.contactUserId, target.accountReference, index, settled = false)
                .apply { inFlight = true }
            if (!write(map)) {
                map.remove(address)
                log.warn("contact payment refused: its address could not be recorded in {}", file)
                return ClaimResult.STORE_UNAVAILABLE
            }
            ClaimResult.CLAIMED
        }

    /**
     * The send that claimed [address] may have broadcast it (or its outcome is
     * unknown): the claim stays, as a paid address. Retries a failed write.
     */
    fun sent(address: String) {
        synchronized(lock) {
            val map = loaded()
            map[address]?.inFlight = false
            if (unwritten) {
                write(map)
            }
        }
    }

    /**
     * The send that claimed [address] failed provably before anything was
     * broadcast: the address was never paid and may be offered again. Only for
     * the claim's own send — and never for an entry dashj has settled. A failed
     * write leaves the claim on disk: the address stays excluded (fail-safe).
     */
    fun release(address: String) {
        synchronized(lock) {
            val map = loaded()
            val entry = map[address] ?: return
            if (entry.settled) {
                entry.inFlight = false
                return
            }
            map.remove(address)
            write(map)
        }
    }

    /**
     * Records [address] as paid now, to [target] at [index] when known, and
     * writes the store before returning (a failed write keeps it in memory).
     * The entry starts unsettled. An address already recorded is left as it
     * is. Production code claims instead ([claim]).
     */
    internal fun record(address: String, target: ContactPaymentTarget? = null, index: Int? = null) {
        synchronized(lock) {
            val map = loaded()
            if (address in map) {
                if (unwritten) write(map)
                return
            }
            prune(map)
            map[address] = Entry(nowMs(), target?.contactUserId, target?.accountReference, index, settled = false)
            write(map)
        }
    }

    /**
     * Records [paid] (address → derivation index on [target]'s channel), which
     * the chain shows paid but nothing here recorded — payments made before a
     * recovery, found by [ContactPaymentAddressProvider] in the SDK's
     * transaction store (PR #1608 review, round 10). Each becomes an entry like
     * a sent payment's (unsettled, not in flight): it counts toward the
     * channel's [paidFrontier], so the next lookup resumes above it, and its
     * dashj mark is applied on the next lookup ([awaitingDashj]) and settles
     * as usual. Addresses already recorded are left as they are. One write; a
     * failed write keeps them in memory (fail-soft: without the record the
     * next lookup finds them on chain again).
     */
    fun recordPaidOnChain(target: ContactPaymentTarget, paid: Map<String, Int>) {
        synchronized(lock) {
            val map = loaded()
            val added = paid.filter { (address, index) -> index >= 0 && address !in map }
            if (added.isEmpty()) {
                if (unwritten) write(map)
                return
            }
            prune(map)
            added.forEach { (address, index) ->
                map[address] = Entry(nowMs(), target.contactUserId, target.accountReference, index, settled = false)
            }
            write(map)
        }
    }

    /**
     * The highest derivation index recorded paid or claimed on the channel —
     * the folded frontier and every live entry with a known index, claims in
     * flight included — or null when none is known. An address at or below it
     * must not be picked.
     */
    fun paidFrontier(contactUserId: String, accountReference: Int): Int? = synchronized(lock) {
        val channel = Channel(contactUserId, accountReference)
        val live = loaded().values.filter { it.channel == channel }.mapNotNull { it.index }.maxOrNull()
        listOfNotNull(folded[channel]?.index, live).maxOrNull()
    }

    /**
     * The channel's folded frontier: the highest paid address whose entry has
     * expired. Independent of the wallet — the provider advances any dashj
     * chain at or below it past it on every lookup. Null when nothing expired.
     */
    fun foldedFrontier(contactUserId: String, accountReference: Int): Frontier? = synchronized(lock) {
        loaded()
        folded[Channel(contactUserId, accountReference)]
    }

    /**
     * The unsettled addresses paid to [contactUserId] on [accountReference]
     * whose dashj mark is to be (re-)applied ([dashjMarked] with the outcome):
     * dashj has not been advanced past them in this process, no send in this
     * process still has them in flight, and dashj did not report in this
     * process that it cannot place them. Oldest first, so a chain dashj built
     * after the payments is advanced in order.
     */
    fun awaitingDashj(contactUserId: String, accountReference: Int): List<String> = synchronized(lock) {
        loaded().filter { (_, e) ->
            !e.settled && !e.inFlight && !e.dashjCannotPlace && e.dashjAdvancedAfterSave == null &&
                e.contactUserId == contactUserId && e.accountReference == accountReference
        }.keys.toList()
    }

    /**
     * What marking [address] on dashj's sending chain returned. Past it
     * ([FriendChainAccess.MarkResult.MARKED], or already behind dashj's current
     * key): the next wallet save to start settles the entry. No chain: nothing
     * changes, the mark is re-applied on the next lookup.
     */
    fun dashjMarked(address: String, outcome: FriendChainAccess.MarkResult?) {
        synchronized(lock) {
            val entry = loaded()[address] ?: return
            when (outcome) {
                FriendChainAccess.MarkResult.MARKED,
                FriendChainAccess.MarkResult.ALREADY_BEHIND_CURRENT -> {
                    if (!entry.settled) {
                        entry.dashjAdvancedAfterSave = walletSaves.lastStarted()
                    }
                }
                FriendChainAccess.MarkResult.NO_CHAIN -> Unit
                FriendChainAccess.MarkResult.KEY_NOT_ON_CHAIN, null -> entry.dashjCannotPlace = true
            }
        }
    }

    /** A wallet save numbered [seq] completed ([WalletSaveTracker]). */
    internal fun onWalletSaved(seq: Long) {
        synchronized(lock) {
            // Not loaded: nothing was advanced on dashj in this process yet.
            val map = entries ?: return
            var changed = false
            map.values.forEach { entry ->
                val advancedAfter = entry.dashjAdvancedAfterSave
                if (!entry.settled && advancedAfter != null && seq > advancedAfter) {
                    entry.settled = true
                    changed = true
                }
            }
            if (changed) {
                prune(map)
                write(map)
            }
        }
    }

    /**
     * The app's wallet was replaced or removed ([WalletSaveTracker.walletChanged]):
     * what dashj recorded durably was in the previous wallet file, and its
     * advances in this process were made on the previous wallet. Every entry
     * is unsettled — loaded WITHOUT expiring anything first, so a settled entry
     * past the retention is kept too — written, and re-applied to the new
     * wallet's chains on the next lookup. The folded frontiers need nothing:
     * they are re-applied to whatever wallet is current on every lookup.
     */
    internal fun onWalletChanged() {
        synchronized(lock) {
            val map = loaded()
            map.values.forEach { entry ->
                entry.settled = false
                entry.dashjAdvancedAfterSave = null
                entry.dashjCannotPlace = false
            }
            // The new wallet may have paid contacts before this store covered
            // it (a recovery phrase used on another device): its history is
            // unknown until the SDK has processed it through the tip.
            val hadHistory = historyWalletId != null
            historyWalletId = null
            historyGeneration++
            if (map.isNotEmpty() || hadHistory) {
                log.info("the wallet changed; {} paid contact address(es) wait for the new wallet's dashj", map.size)
                write(map)
            }
        }
    }

    /**
     * Whether every contact payment made from SDK wallet [walletId] is known:
     * recorded here, or in the SDK's transaction store because the SDK
     * processed the wallet through the tip after this store began covering it
     * ([markPaidHistoryKnown]). False for a store without that record — no
     * file, a file written before the record existed, or one whose record is
     * for another wallet — and after a wallet change ([onWalletChanged]).
     */
    fun paidHistoryKnown(walletId: String): Boolean = synchronized(lock) {
        loaded()
        !readFailed && historyWalletId == walletId
    }

    /**
     * Whether the file exists but could not be read: every [claim] is then
     * [ClaimResult.STORE_UNAVAILABLE] and [paidHistoryKnown] false.
     */
    fun unreadable(): Boolean = synchronized(lock) {
        loaded()
        readFailed
    }

    /**
     * The marker's generation: read it BEFORE reading the evidence that the
     * paid-address history is known, and pass it to [markPaidHistoryKnown].
     */
    fun paidHistoryGeneration(): Long = synchronized(lock) { historyGeneration }

    /**
     * The SDK has processed [walletId] through the chain tip: its transaction
     * store holds every payment made before this store began covering the
     * wallet, so the paid-address history is known from now on (see
     * [paidHistoryKnown]). Written at once; a failed write keeps it in memory
     * for this process (the next one waits for the scan again).
     *
     * Refused (false) when the marker was dropped since [generation] was read
     * ([paidHistoryGeneration]): the evidence was then read from a transaction
     * store that has been deleted or replaced since — under the same wallet
     * id when the wallet was re-created (PR #1608 review, round 13). Refused
     * too while a deletion is in progress ([beginHistoryInvalidation]): the
     * evidence may be about the store being deleted (round 14). True when the
     * marker is recorded (or already was).
     */
    fun markPaidHistoryKnown(walletId: String, generation: Long): Boolean {
        require(walletId.isNotEmpty() && walletId.none { it.isWhitespace() }) { "invalid wallet id" }
        synchronized(lock) {
            val map = loaded()
            if (generation != historyGeneration) {
                log.info("the paid-address history marker was dropped while it was being established; not recorded")
                return false
            }
            if (invalidationsInProgress > 0) {
                log.info("the SDK's transaction store is being deleted; the paid-address history marker is not recorded")
                return false
            }
            if (historyWalletId == walletId) return true
            historyWalletId = walletId
            write(map)
            return true
        }
    }

    /**
     * The SDK's transaction store for [walletId] is about to be deleted (its
     * wallet re-created under the same id, or its L1 rows cleared): what that
     * store vouched for is gone, so the paid-address history is unknown again
     * until the rebuilt store has been processed through the tip (PR #1608
     * review, round 12). The marker names the seed-derived wallet, which the
     * re-created wallet shares, so it has to be dropped here.
     *
     * True when no marker for [walletId] is left on disk — dropped and
     * written, or never there. False when the file cannot be read (it may
     * hold the marker, and the next process could read it) or the write
     * failed: the caller must not delete the store then. The marker is
     * dropped in memory either way, and a lookup that read its evidence
     * before this call can no longer record it ([markPaidHistoryKnown]).
     * Blocking.
     */
    fun forgetPaidHistory(walletId: String): Boolean = synchronized(lock) {
        dropPaidHistory(walletId)
    }

    /**
     * The SDK's transaction store for [walletId] is about to be deleted:
     * drops the marker ([forgetPaidHistory]) and, atomically with it, stops
     * [markPaidHistoryKnown] from recording it until [endHistoryInvalidation].
     * A lookup that starts in between reads the store being deleted, which
     * still looks processed through the tip (PR #1608 review, round 14).
     *
     * Returns what [forgetPaidHistory] returns: false means the store must not
     * be deleted. Either way — false, or a throw — the caller must call
     * [endHistoryInvalidation] exactly once afterwards (in a `finally`), or
     * the marker can never be recorded again in this process. Blocking.
     */
    fun beginHistoryInvalidation(walletId: String): Boolean = synchronized(lock) {
        invalidationsInProgress++
        dropPaidHistory(walletId)
    }

    /**
     * The deletion begun by [beginHistoryInvalidation] is over, done or
     * failed: drops the marker again, in case a lookup recorded it from the
     * old store's state just before the deletion began, then lets
     * [markPaidHistoryKnown] record it again. Returns what [forgetPaidHistory]
     * returns; the marker is dropped in memory and recording reopens either
     * way. Blocking.
     */
    fun endHistoryInvalidation(walletId: String): Boolean = synchronized(lock) {
        try {
            dropPaidHistory(walletId)
        } finally {
            // At most once per begin; an end whose begin never ran (the
            // caller was cancelled before it) must not go below zero.
            invalidationsInProgress = (invalidationsInProgress - 1).coerceAtLeast(0)
        }
    }

    /** [forgetPaidHistory], under [lock]. */
    private fun dropPaidHistory(walletId: String): Boolean {
        historyGeneration++
        val map = loaded()
        if (readFailed) {
            log.warn("could not read {}; the paid-address history marker cannot be dropped", file)
            return false
        }
        // Not in memory, but a write that failed earlier (this drop's own, or
        // any other) may have left a marker on disk: write again until one
        // succeeds, or a retry would report a drop that never reached disk.
        if (historyWalletId != walletId) return !unwritten || write(map)
        historyWalletId = null
        log.info("the SDK's transaction store is being rebuilt; the paid-address history is unknown again")
        return write(map)
    }

    /** Whether [address] has an entry and it is settled; null without an entry. For tests and logs. */
    internal fun isSettled(address: String): Boolean? = synchronized(lock) {
        loaded()[address]?.settled
    }

    /** Loads the file once. Never expires anything: that happens only on a write ([prune]). */
    private fun loaded(): LinkedHashMap<String, Entry> {
        val current = entries
        if (current != null) {
            return current
        }
        val map = LinkedHashMap<String, Entry>()
        val content = read()
        content.entries.sortedBy { it.second.paidAt }.forEach { (address, entry) -> map[address] = entry }
        content.frontiers.forEach { (channel, frontier) -> foldInto(channel, frontier) }
        historyWalletId = content.historyWalletId
        val pending = map.values.count { !it.settled }
        if (pending > PENDING_WARN) {
            log.warn("{} paid contact addresses are not yet confirmed by dashj", pending)
        }
        entries = map
        return map
    }

    private fun foldInto(channel: Channel, frontier: Frontier) {
        val current = folded[channel]
        if (current == null || frontier.index > current.index) {
            folded[channel] = frontier
        }
    }

    /**
     * Expires SETTLED entries that can be folded into their channel's frontier
     * — by age, then the oldest beyond [MAX_ENTRIES] — folding each one. Never
     * an unsettled entry, and never one without a channel and index.
     */
    private fun prune(map: LinkedHashMap<String, Entry>) {
        val cutoff = nowMs() - RETENTION_MS
        fun expire(address: String, entry: Entry) {
            val channel = checkNotNull(entry.channel) { "only foldable entries expire" }
            val index = checkNotNull(entry.index) { "only foldable entries expire" }
            foldInto(channel, Frontier(index, address))
        }
        val iterator = map.entries.iterator()
        while (iterator.hasNext()) {
            val (address, entry) = iterator.next()
            if (entry.settled && entry.foldable && entry.paidAt < cutoff) {
                expire(address, entry)
                iterator.remove()
            }
        }
        var excess = map.values.count { it.settled && it.foldable } - MAX_ENTRIES
        if (excess > 0) {
            val oldest = map.entries.iterator()
            while (excess > 0 && oldest.hasNext()) {
                val (address, entry) = oldest.next()
                if (entry.settled && entry.foldable) {
                    expire(address, entry)
                    oldest.remove()
                    excess--
                }
            }
        }
    }

    private class Content(
        val entries: List<Pair<String, Entry>>,
        val frontiers: List<Pair<Channel, Frontier>>,
        val historyWalletId: String? = null
    )

    private fun read(): Content {
        val empty = Content(emptyList(), emptyList())
        val f = file ?: return empty
        return try {
            if (!f.isFile) {
                empty
            } else {
                val lines = f.readLines()
                Content(
                    entries = lines.mapNotNull(::parseEntry),
                    frontiers = lines.mapNotNull(::parseFrontier),
                    historyWalletId = lines.firstNotNullOfOrNull(::parseHistory)
                )
            }
        } catch (e: Exception) {
            log.warn("could not read {}; contact payments are refused, the file is left as it is", f, e)
            readFailed = true
            empty
        }
    }

    private fun parseHistory(line: String): String? {
        val parts = line.trim().split(' ')
        if (parts.size != 2 || parts[0] != HISTORY) return null
        return parts[1].takeIf { it.isNotEmpty() }
    }

    private fun parseFrontier(line: String): Pair<Channel, Frontier>? {
        val parts = line.trim().split(' ')
        if (parts.size != 5 || parts[0] != FRONTIER) return null
        val contact = parts[1].takeIf { it.isNotEmpty() } ?: return null
        val reference = parts[2].toIntOrNull() ?: return null
        val index = parts[3].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val address = parts[4].takeIf { it.isNotEmpty() } ?: return null
        return Channel(contact, reference) to Frontier(index, address)
    }

    private fun parseEntry(line: String): Pair<String, Entry>? {
        val parts = line.trim().split(' ')
        val at = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val address = parts.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
        return when (parts.size) {
            // earlier builds: no state, no contact
            2 -> address to Entry(at, null, null, null, settled = false)
            3, 5, 6 -> {
                val settled = when (parts[2]) {
                    SETTLED -> true
                    PENDING -> false
                    else -> return null
                }
                val contact = parts.getOrNull(3)?.takeIf { it.isNotEmpty() }
                val reference = parts.getOrNull(4)?.toIntOrNull()
                if (parts.size >= 5 && (contact == null || reference == null)) return null
                val index = if (parts.size == 6) {
                    parts[5].toIntOrNull()?.takeIf { it >= 0 } ?: return null
                } else {
                    null
                }
                address to Entry(at, contact, reference, index, settled)
            }
            else -> null
        }
    }

    /**
     * Replaces the file: writes and fsyncs `<file>.tmp`, then renames it over
     * the file, so a kill or power loss leaves the old or the new content,
     * never a partial one. Under [AtomicFileWriter.runExclusive], so the
     * launch sweep of abandoned `*.tmp` files (WalletApplication.cleanupFiles)
     * cannot delete the temp mid-write. The file itself is never deleted: a
     * failed rename keeps the previous content on disk.
     *
     * True when the content is on disk (or the store is memory-only); false
     * when the write failed or the file could not be read (and so is never
     * written).
     */
    private fun write(map: Map<String, Entry>): Boolean {
        val f = file ?: return true
        if (readFailed) return false
        val historyLine = historyWalletId?.let { "$HISTORY $it\n" }.orEmpty()
        val frontierLines = folded.entries.joinToString(separator = "") { (channel, frontier) ->
            "$FRONTIER ${channel.contactUserId} ${channel.accountReference} ${frontier.index} ${frontier.address}\n"
        }
        val entryLines = map.entries.joinToString(separator = "") { (address, e) ->
            val state = if (e.settled) SETTLED else PENDING
            val channel = if (e.contactUserId != null && e.accountReference != null) {
                " ${e.contactUserId} ${e.accountReference}" + (e.index?.let { " $it" } ?: "")
            } else {
                ""
            }
            "${e.paidAt} $address $state$channel\n"
        }
        unwritten = true
        try {
            AtomicFileWriter.runExclusive {
                f.parentFile?.mkdirs()
                val tmp = File(f.path + AtomicFileWriter.TEMP_SUFFIX)
                FileOutputStream(tmp).use { out ->
                    out.write((historyLine + frontierLines + entryLines).toByteArray(Charsets.UTF_8))
                    out.flush()
                    out.fd.sync()
                }
                if (tmp.renameTo(f)) {
                    unwritten = false
                } else {
                    tmp.delete()
                    log.warn("could not replace {}; the paid addresses are remembered in memory only", f)
                }
            }
        } catch (e: Exception) {
            log.warn("could not write {}; the paid addresses are remembered in memory only", f, e)
        }
        return !unwritten
    }

    companion object {
        private val log = LoggerFactory.getLogger(PaidContactAddressStore::class.java)

        /**
         * How long a SETTLED entry is kept before it is folded into its
         * channel's frontier; unsettled ones are never folded.
         */
        val RETENTION_MS: Long = TimeUnit.DAYS.toMillis(7)

        /** A bound on SETTLED foldable entries; the oldest beyond it are folded. */
        const val MAX_ENTRIES = 500

        /** More unsettled entries than this is unexpected; logged on load. */
        private const val PENDING_WARN = 100

        private const val SETTLED = "settled"
        private const val PENDING = "pending"
        private const val FRONTIER = "frontier"
        private const val HISTORY = "history"

        const val FILE_NAME = "contact_paid_addresses.txt"

        @Volatile
        private var app: PaidContactAddressStore? = null

        /**
         * The app's store: [FILE_NAME] in [filesDir], settled by the app
         * wallet's saves ([WalletSaveTracker.APP]). One instance per process,
         * so a wallet change reaches it whether or not a send screen created
         * it yet.
         */
        @JvmStatic
        fun app(filesDir: File): PaidContactAddressStore =
            app ?: synchronized(this) {
                app ?: PaidContactAddressStore(File(filesDir, FILE_NAME), walletSaves = WalletSaveTracker.APP)
                    .also { app = it }
            }

        /**
         * The app's wallet was replaced or removed (wipe, restore from the
         * recovery phrase, recovery from the key backup). Blocking (file
         * read/write); call outside any [AtomicFileWriter] lock.
         */
        @JvmStatic
        fun appWalletChanged(filesDir: File) {
            app(filesDir)
            WalletSaveTracker.APP.walletChanged()
        }
    }
}
