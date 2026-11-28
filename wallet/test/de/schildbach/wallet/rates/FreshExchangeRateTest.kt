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

package de.schildbach.wallet.rates

import de.schildbach.wallet.database.dao.ExchangeRatesDao
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.data.ExchangeRatesConfig
import org.dash.wallet.common.data.entity.ExchangeRate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** A rate that money is checked against must itself be recent, whatever the last refresh says. */
class FreshExchangeRateTest {
    private val dao = mockk<ExchangeRatesDao>()
    private val config = mockk<ExchangeRatesConfig>(relaxed = true) {
        // the rates as a whole were retrieved just now
        coEvery { get(ExchangeRatesConfig.EXCHANGE_RATES_RETRIEVAL_TIME) } answers { System.currentTimeMillis() }
    }

    private lateinit var repository: ExchangeRatesRepository

    @Before
    fun setUp() {
        // the real rate sources cannot be built outside the app, and none is to be called here
        mockkStatic(DashRetailClient::class, DashRatesSecondFallback::class)
        every { DashRetailClient.getInstance() } returns mockk()
        every { DashRatesSecondFallback.getInstance() } returns mockk()
        repository = ExchangeRatesRepository(dao, config)
        // refreshed a moment ago, so only the stored row counts
        ExchangeRatesRepository::class.java.getDeclaredField("lastUpdated").apply {
            isAccessible = true
            setLong(repository, System.currentTimeMillis())
        }
    }

    @After
    fun tearDown() {
        unmockkStatic(DashRetailClient::class, DashRatesSecondFallback::class)
    }

    private fun storedRate(retrievedMinutesAgo: Long?) = ExchangeRate("GBP", "30").apply {
        retrievedMinutesAgo?.let { retrievalTime = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(it) }
    }.also { coEvery { dao.getExchangeRateForCurrency("GBP") } returns it }

    @Test
    fun rowLeftBehindByAnEarlierRefresh_isNotFresh() = runBlocking {
        storedRate(retrievedMinutesAgo = 31)

        assertNull(repository.getFreshExchangeRate("GBP", 1))
    }

    @Test
    fun rowOfTheLatestRefresh_isFresh() = runBlocking {
        val rate = storedRate(retrievedMinutesAgo = 29)

        assertEquals(rate, repository.getFreshExchangeRate("GBP", 1))
    }

    @Test
    fun rowWithoutATimeOfItsOwn_goesByTheTimeOfTheLastRefresh() = runBlocking {
        val rate = storedRate(retrievedMinutesAgo = null)

        assertEquals(rate, repository.getFreshExchangeRate("GBP", 1))
    }
}
