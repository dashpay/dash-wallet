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

import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Contact payment addresses (base58) paid recently, written to disk the moment
 * the payment is known to be broadcast, so [ContactPaymentAddressProvider] skips
 * them after a restart too.
 *
 * It bridges the time until the two durable records of a payment catch up:
 * the SDK's Room mirror flips the address's used flag one persistence pass
 * after the engine sees the payment, and dashj's advanced sending cursor
 * ([org.bitcoinj.wallet.FriendChainAccess.markSendingAddressUsed]) reaches disk
 * on the next wallet autosave — up to a minute later on a large wallet
 * (WalletFileSizeGuard.autosaveDelayMs). A process killed inside that window
 * would otherwise come back to the same lowest unused address. Entries are
 * therefore kept only for [RETENTION_MS] and at most [MAX_ENTRIES] of them.
 *
 * Scoping: a contact payment address is derived from the xpub that contact
 * gave THIS identity for ONE contact request (accountReference), so an address
 * already names its wallet and channel; another wallet or channel never
 * produces it.
 *
 * File format: one `<paidAtMillis> <base58>` per line, replaced atomically
 * (write a temp file, rename). [file] null keeps the set in memory only.
 * Never throws: a failed read starts empty, a failed write keeps the entry in
 * memory and logs.
 */
class PaidContactAddressStore(
    private val file: File?,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private val lock = Any()

    /** address → paidAtMillis, oldest first; loaded on first use. */
    private var entries: LinkedHashMap<String, Long>? = null

    /** The addresses paid within [RETENTION_MS]. */
    fun paid(): Set<String> = synchronized(lock) {
        loaded().keys.toSet()
    }

    /** Records [address] as paid now and writes the store before returning. */
    fun record(address: String) {
        synchronized(lock) {
            val map = loaded()
            map.remove(address)
            map[address] = nowMs()
            prune(map)
            write(map)
        }
    }

    private fun loaded(): LinkedHashMap<String, Long> {
        val current = entries
        if (current != null) {
            prune(current)
            return current
        }
        val map = LinkedHashMap<String, Long>()
        read().sortedBy { it.second }.forEach { (address, at) -> map[address] = at }
        prune(map)
        entries = map
        return map
    }

    private fun prune(map: LinkedHashMap<String, Long>) {
        val cutoff = nowMs() - RETENTION_MS
        map.entries.removeAll { it.value < cutoff }
        while (map.size > MAX_ENTRIES) {
            map.remove(map.keys.first())
        }
    }

    private fun read(): List<Pair<String, Long>> {
        val f = file ?: return emptyList()
        return try {
            if (!f.isFile) {
                emptyList()
            } else {
                f.readLines().mapNotNull { line ->
                    val parts = line.trim().split(' ')
                    val at = parts.getOrNull(0)?.toLongOrNull()
                    val address = parts.getOrNull(1)
                    if (parts.size == 2 && at != null && !address.isNullOrEmpty()) address to at else null
                }
            }
        } catch (e: Exception) {
            log.warn("could not read {}; starting empty", f, e)
            emptyList()
        }
    }

    private fun write(map: Map<String, Long>) {
        val f = file ?: return
        try {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(map.entries.joinToString(separator = "") { (address, at) -> "$at $address\n" })
            if (!tmp.renameTo(f)) {
                f.delete()
                if (!tmp.renameTo(f)) {
                    log.warn("could not replace {}", f)
                }
            }
        } catch (e: Exception) {
            log.warn("could not write {}; the paid address is remembered in memory only", f, e)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(PaidContactAddressStore::class.java)

        /** Far longer than either stack takes to persist a payment. */
        val RETENTION_MS: Long = TimeUnit.DAYS.toMillis(7)

        /** A bound on the file; a week of contact payments stays well below it. */
        const val MAX_ENTRIES = 500

        const val FILE_NAME = "contact_paid_addresses.txt"
    }
}
