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

import de.schildbach.wallet.util.WalletSaveTracker
import org.bitcoinj.wallet.FriendChainAccess
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Contact payment addresses (base58) that have been paid, written to disk the
 * moment the payment is known to be broadcast, so [ContactPaymentAddressProvider]
 * never hands one out again — across restarts too.
 *
 * It covers the time until a stack that could serve the address again has
 * recorded the payment durably:
 * - dashj: [FriendChainAccess.markSendingAddressUsed] advances the contact's
 *   sending chain in memory and only schedules a wallet save (up to a minute
 *   on a large wallet, WalletFileSizeGuard.autosaveDelayMs). A process that
 *   dies first comes back at the paid key. An entry is SETTLED once dashj has
 *   been advanced past it ([dashjMarked]) and a wallet save that started after
 *   that has completed ([WalletSaveTracker]) — dashj's persisted chain is
 *   then past it.
 * - the SDK alone: when dashj has no chain for the contact (it can never serve
 *   the address), an entry is settled once the SDK's Room mirror, which is
 *   durable, flags the address used ([sdkMarkedUsed]).
 *
 * Only settled entries expire ([RETENTION_MS], at most [MAX_ENTRIES] of them,
 * oldest first): they are redundant, and kept a while only as a margin. An
 * unsettled entry is NEVER dropped by age or count — dropping it is what would
 * let dashj, reloaded at the paid key, hand it out again (PR #1608 review).
 *
 * Why the file stays small: an entry settles with the first wallet save after
 * its payment (normally within a minute, while the app is open), or when the
 * SDK flags the address. One that misses that — the process died before the
 * save — is retried: each [ContactPaymentAddressProvider.nextAddress] for that
 * contact first re-applies the dashj mark of its unsettled entries
 * ([awaitingDashj]), and the next save settles them. What can stay unsettled
 * is an address neither stack can confirm (dashj has a chain but the key is
 * not on it, or there is no dashj identity) — at most one line per such
 * payment, never more.
 *
 * Scoping: a contact payment address is derived from the xpub that contact
 * gave THIS identity for ONE contact request (accountReference), so an address
 * already names its wallet and channel; another wallet or channel never
 * produces it. The contact and accountReference are stored only to re-apply
 * the dashj mark.
 *
 * File format: one line per address,
 * `<paidAtMillis> <base58> <pending|settled> [<contactUserId> <accountReference>]`,
 * replaced atomically (write a temp file, rename). A line of just
 * `<paidAtMillis> <base58>` (earlier builds) reads as pending with no contact.
 * [file] null keeps the set in memory only. Never throws: a failed read starts
 * empty, a failed write keeps the entries in memory and logs.
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

        /** dashj reported, in this process, that it has no chain for the contact. */
        var dashjHasNoChain: Boolean = false
    }

    /** address → entry, oldest first; loaded on first use. */
    private var entries: LinkedHashMap<String, Entry>? = null

    init {
        walletSaves.addListener(::onWalletSaved)
    }

    /** Every address recorded paid and not yet expired. */
    fun paid(): Set<String> = synchronized(lock) {
        loaded().keys.toSet()
    }

    /**
     * Records [address] as paid now, to [target] when known, and writes the
     * store before returning. The entry starts unsettled.
     */
    fun record(address: String, target: ContactPaymentTarget? = null) {
        synchronized(lock) {
            val map = loaded()
            map.remove(address)
            map[address] = Entry(nowMs(), target?.contactUserId, target?.accountReference, settled = false)
            prune(map)
            write(map)
        }
    }

    /**
     * The unsettled addresses paid to [contactUserId] on [accountReference]
     * that dashj has not been advanced past in this process — the marks to
     * re-apply ([dashjMarked] with the outcome).
     */
    fun awaitingDashj(contactUserId: String, accountReference: Int): List<String> = synchronized(lock) {
        loaded().filter { (_, e) ->
            !e.settled && e.dashjAdvancedAfterSave == null &&
                e.contactUserId == contactUserId && e.accountReference == accountReference
        }.keys.toList()
    }

    /**
     * What marking [address] on dashj's sending chain returned. Past it
     * ([FriendChainAccess.MarkResult.MARKED], or already behind dashj's current
     * key): the next wallet save to start settles the entry.
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
                    entry.dashjHasNoChain = false
                }
                FriendChainAccess.MarkResult.NO_CHAIN -> entry.dashjHasNoChain = true
                // Unknown: dashj neither has it nor can be ruled out.
                FriendChainAccess.MarkResult.KEY_NOT_ON_CHAIN, null -> Unit
            }
        }
    }

    /**
     * [usedAddresses] are flagged used in the SDK's Room mirror. Settles those
     * whose contact dashj has no chain for (reported to [dashjMarked] in this
     * process): no stack can serve them again.
     */
    fun sdkMarkedUsed(usedAddresses: Collection<String>) {
        if (usedAddresses.isEmpty()) return
        synchronized(lock) {
            val map = loaded()
            var changed = false
            usedAddresses.forEach { address ->
                val entry = map[address]
                if (entry != null && !entry.settled && entry.dashjHasNoChain) {
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
            log.warn("{} paid contact addresses are not yet confirmed by dashj or the SDK", pending)
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
            log.warn("could not read {}; starting empty", f, e)
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

    private fun write(map: Map<String, Entry>) {
        val f = file ?: return
        try {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(
                map.entries.joinToString(separator = "") { (address, e) ->
                    val state = if (e.settled) SETTLED else PENDING
                    val channel = if (e.contactUserId != null && e.accountReference != null) {
                        " ${e.contactUserId} ${e.accountReference}"
                    } else {
                        ""
                    }
                    "${e.paidAt} $address $state$channel\n"
                }
            )
            if (!tmp.renameTo(f)) {
                f.delete()
                if (!tmp.renameTo(f)) {
                    log.warn("could not replace {}", f)
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
    }
}
