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

package de.schildbach.wallet.service

import de.schildbach.wallet.database.entity.TxDisplayCacheEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for [matchesFilterFlag] — the pure predicate [TxDisplayCacheService] uses to
 * keep its fast-path cache snapshot ([TxDisplayCacheService.cachedRows]) in agreement with the
 * live Paging adapter's SQL WHERE clause (`TxDisplayCacheDao.pagingSource`) on which rows a
 * direction filter (ALL/SENT/RECEIVED/GIFT_CARD) lets through.
 *
 * MO-1109: picking "Received" or "Sent" in the Filter Transactions dialog persisted the choice
 * but never changed the rows on screen. One cause was this exact bitmask test never being
 * applied to the cache snapshot at all (it only unfiltered-echoed every row); these cases pin
 * the bitmask semantics so the cache path and the live path cannot drift apart again.
 */
class TxDisplayCacheFilterFlagTest {

    @Test
    fun allFilterMatchesEveryEntryRegardlessOfItsFlags() {
        assertTrue(matchesFilterFlag(entryFlags = 0, filterFlag = 0))
        assertTrue(matchesFilterFlag(entryFlags = TxDisplayCacheEntry.FLAG_SENT, filterFlag = 0))
        assertTrue(matchesFilterFlag(entryFlags = TxDisplayCacheEntry.FLAG_RECEIVED, filterFlag = 0))
        assertTrue(matchesFilterFlag(entryFlags = TxDisplayCacheEntry.FLAG_GIFT_CARD, filterFlag = 0))
    }

    @Test
    fun sentFilterOnlyMatchesSentEntries() {
        val sent = TxDisplayCacheEntry.FLAG_SENT
        assertTrue(matchesFilterFlag(entryFlags = sent, filterFlag = sent))
        assertFalse(matchesFilterFlag(entryFlags = TxDisplayCacheEntry.FLAG_RECEIVED, filterFlag = sent))
    }

    @Test
    fun receivedFilterOnlyMatchesReceivedEntries() {
        val received = TxDisplayCacheEntry.FLAG_RECEIVED
        assertTrue(matchesFilterFlag(entryFlags = received, filterFlag = received))
        assertFalse(matchesFilterFlag(entryFlags = TxDisplayCacheEntry.FLAG_SENT, filterFlag = received))
    }

    @Test
    fun giftCardFilterOnlyMatchesGiftCardEntries() {
        // A gift-card purchase also carries FLAG_SENT (TxDisplayCacheService.computeFilterFlags),
        // so it must still surface under the GIFT_CARD filter even though it is not a bare sent row.
        val giftCard = TxDisplayCacheEntry.FLAG_GIFT_CARD
        val giftCardSpend = giftCard or TxDisplayCacheEntry.FLAG_SENT
        assertTrue(matchesFilterFlag(entryFlags = giftCardSpend, filterFlag = giftCard))
        assertFalse(matchesFilterFlag(entryFlags = TxDisplayCacheEntry.FLAG_SENT, filterFlag = giftCard))
    }

    @Test
    fun coinJoinEntriesAreExcludedFromEverySingleDirectionFilter() {
        // computeFilterFlags gives a CoinJoin mixing group ONLY FLAG_COINJOIN, no SENT/RECEIVED —
        // it must not leak into either direction filter.
        val coinJoin = TxDisplayCacheEntry.FLAG_COINJOIN
        assertFalse(matchesFilterFlag(entryFlags = coinJoin, filterFlag = TxDisplayCacheEntry.FLAG_SENT))
        assertFalse(matchesFilterFlag(entryFlags = coinJoin, filterFlag = TxDisplayCacheEntry.FLAG_RECEIVED))
        assertTrue(matchesFilterFlag(entryFlags = coinJoin, filterFlag = 0))
    }
}
