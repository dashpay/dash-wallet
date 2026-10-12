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
 * concurrent sends, restarts and process deaths at any step.
 *
 * One entry per address. It is created by [claim], atomically with the check
 * that no entry exists, before the send starts: a second send of the same
 * address (another send screen) is refused, and a process that dies mid-send
 * comes back with the address excluded. A send that fails provably before
 * anything was broadcast gives the address back ([release]); every other
 * outcome keeps it.
 *
 * An entry is kept until the stack that could serve the address again has
 * recorded the payment durably: dashj.
 * [FriendChainAccess.markSendingAddressUsed] advances the contact's sending
 * chain in memory and only schedules a wallet save (up to a minute on a large
 * wallet), so a process that dies first comes back at the paid key. An entry is
 * SETTLED once dashj has been advanced past it ([dashjMarked]) and a wallet
 * save that started after that has completed ([WalletSaveTracker]) — dashj's
 * persisted chain is then past it, and the SDK's pick never goes below dashj's
 * persisted cursor. Only settled entries expire ([RETENTION_MS], at most
 * [MAX_ENTRIES] of them, oldest first).
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
 * or removed (wipe, restore, recovery from the key backup — reported through
 * [WalletSaveTracker.walletChanged]; the app's store is created for it if
 * need be, see [appWalletChanged]) every entry becomes unsettled again, and is re-applied to the new
 * wallet's chains and settled by its saves like a new one.
 *
 * Why the file stays small: an entry settles with the first wallet save after
 * its payment (normally within a minute while the app is open). One that misses
 * that is retried on the contact's next address lookup. What stays unsettled is
 * a payment dashj cannot place — no chain yet for the contact, the key not on
 * its chain, or no dashj identity — at most one line per such payment.
 *
 * Scoping: a contact payment address is derived from the xpub that contact
 * gave THIS identity for ONE contact request (accountReference), so an address
 * names its wallet and channel. The contact and accountReference are stored
 * only to re-apply the dashj mark.
 *
 * File format: one line per address,
 * `<paidAtMillis> <base58> <pending|settled> [<contactUserId> <accountReference>]`,
 * replaced atomically (write and fsync a temp file, rename). A line of just
 * `<paidAtMillis> <base58>` (earlier builds) reads as pending with no contact.
 * [file] null keeps the set in memory only. Never throws: a failed read starts
 * empty in memory and leaves the file untouched for the next process; a failed
 * write keeps the entries in memory, logs, and is retried by the next change.
 */
class PaidContactAddressStore(
    private val file: File?,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val walletSaves: WalletSaveTracker = WalletSaveTracker()
) {
    private val lock = Any()

    private class Entry(
        val paidAt: Long,
        val contactUserId: String?,
        val accountReference: Int?,
        var settled: Boolean
    ) {
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

    /** The last write failed: the next change, or [sent], writes again. */
    private var unwritten = false

    /**
     * The file exists but could not be read: this process keeps its entries
     * in memory only and never overwrites the file, so the exclusions it holds
     * are there again for the next process.
     */
    private var readFailed = false

    init {
        walletSaves.addListener(::onWalletSaved)
        walletSaves.addWalletChangedListener(::onWalletChanged)
    }

    /** Every address recorded paid or claimed by a send, and not yet expired. */
    fun paid(): Set<String> = synchronized(lock) {
        loaded().keys.toSet()
    }

    /** Whether [address] is recorded paid or claimed by a send. */
    fun contains(address: String): Boolean = synchronized(lock) {
        address in loaded()
    }

    /**
     * Claims [address] for a payment to [target] about to be sent: if nothing
     * is recorded for it, records it (unsettled, in flight) and writes the
     * store before returning true. False — nothing changed — when it is
     * already paid or claimed by another send. Check and record are one atomic
     * step, so of two concurrent claims of one address exactly one wins.
     */
    fun claim(address: String, target: ContactPaymentTarget): Boolean = synchronized(lock) {
        val map = loaded()
        if (address in map) return false
        map[address] = Entry(nowMs(), target.contactUserId, target.accountReference, settled = false).apply {
            inFlight = true
        }
        prune(map)
        write(map)
        true
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
     * the claim's own send — and never for an entry dashj has settled.
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
     * Records [address] as paid now, to [target] when known, and writes the
     * store before returning. The entry starts unsettled. An address already
     * recorded is left as it is. Production code claims instead ([claim]).
     */
    internal fun record(address: String, target: ContactPaymentTarget? = null) {
        synchronized(lock) {
            val map = loaded()
            if (address in map) {
                if (unwritten) write(map)
                return
            }
            map[address] = Entry(nowMs(), target?.contactUserId, target?.accountReference, settled = false)
            prune(map)
            write(map)
        }
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
     * is unsettled, written, and re-applied to the new wallet's chains on the
     * next lookup. Loads the store if need be.
     */
    internal fun onWalletChanged() {
        synchronized(lock) {
            val map = loaded()
            map.values.forEach { entry ->
                entry.settled = false
                entry.dashjAdvancedAfterSave = null
                entry.dashjCannotPlace = false
            }
            if (map.isNotEmpty()) {
                log.info("the wallet changed; {} paid contact address(es) wait for the new wallet's dashj", map.size)
                write(map)
            }
        }
    }

    /** Whether [address] is recorded and settled; for tests and logs. */
    internal fun isSettled(address: String): Boolean? = synchronized(lock) {
        loaded()[address]?.settled
    }

    private fun loaded(): LinkedHashMap<String, Entry> {
        val current = entries
        if (current != null) {
            return current
        }
        val map = LinkedHashMap<String, Entry>()
        read().sortedBy { it.second.paidAt }.forEach { (address, entry) -> map[address] = entry }
        prune(map)
        val pending = map.values.count { !it.settled }
        if (pending > PENDING_WARN) {
            log.warn("{} paid contact addresses are not yet confirmed by dashj", pending)
        }
        entries = map
        return map
    }

    /** Expires SETTLED entries only: by age, then the oldest beyond [MAX_ENTRIES]. */
    private fun prune(map: LinkedHashMap<String, Entry>) {
        val cutoff = nowMs() - RETENTION_MS
        map.entries.removeAll { it.value.settled && it.value.paidAt < cutoff }
        var excess = map.values.count { it.settled } - MAX_ENTRIES
        if (excess > 0) {
            val iterator = map.entries.iterator()
            while (excess > 0 && iterator.hasNext()) {
                if (iterator.next().value.settled) {
                    iterator.remove()
                    excess--
                }
            }
        }
    }

    private fun read(): List<Pair<String, Entry>> {
        val f = file ?: return emptyList()
        return try {
            if (!f.isFile) {
                emptyList()
            } else {
                f.readLines().mapNotNull(::parse)
            }
        } catch (e: Exception) {
            log.warn("could not read {}; starting empty in memory, the file is left as it is", f, e)
            readFailed = true
            emptyList()
        }
    }

    private fun parse(line: String): Pair<String, Entry>? {
        val parts = line.trim().split(' ')
        val at = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val address = parts.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
        return when (parts.size) {
            // earlier builds: no state, no contact
            2 -> address to Entry(at, null, null, settled = false)
            3, 5 -> {
                val settled = when (parts[2]) {
                    SETTLED -> true
                    PENDING -> false
                    else -> return null
                }
                val contact = parts.getOrNull(3)?.takeIf { it.isNotEmpty() }
                val reference = parts.getOrNull(4)?.toIntOrNull()
                if (parts.size == 5 && (contact == null || reference == null)) return null
                address to Entry(at, contact, reference, settled)
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
     */
    private fun write(map: Map<String, Entry>) {
        val f = file ?: return
        if (readFailed) return
        val body = map.entries.joinToString(separator = "") { (address, e) ->
            val state = if (e.settled) SETTLED else PENDING
            val channel = if (e.contactUserId != null && e.accountReference != null) {
                " ${e.contactUserId} ${e.accountReference}"
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
                    out.write(body.toByteArray(Charsets.UTF_8))
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
    }

    companion object {
        private val log = LoggerFactory.getLogger(PaidContactAddressStore::class.java)

        /** How long a SETTLED entry is kept as a margin; unsettled ones never expire. */
        val RETENTION_MS: Long = TimeUnit.DAYS.toMillis(7)

        /** A bound on SETTLED entries; unsettled ones are never dropped. */
        const val MAX_ENTRIES = 500

        /** More unsettled entries than this is unexpected; logged on load. */
        private const val PENDING_WARN = 100

        private const val SETTLED = "settled"
        private const val PENDING = "pending"

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
