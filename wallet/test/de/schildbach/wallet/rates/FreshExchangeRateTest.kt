package de.schildbach.wallet.rates

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.schildbach.wallet.database.AppDatabase
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.data.entity.ExchangeRate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A refresh replaces the rows it has rates for and leaves every other row as it was. Money is
 * only checked against a rate the last refresh itself returned.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [29], manifest = Config.NONE)
class FreshExchangeRateTest {
    private lateinit var db: AppDatabase
    private val source = mockk<DashRetailClient>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        // the real rate sources cannot be built outside the app
        mockkStatic(DashRetailClient::class, DashRatesSecondFallback::class)
        every { DashRetailClient.getInstance() } returns source
        every { DashRatesSecondFallback.getInstance() } returns mockk()
        // left over from a day when the source still had pounds
        runBlocking { db.exchangeRatesDao().insertAll(listOf(ExchangeRate("GBP", "45"))) }
    }

    @After
    fun tearDown() {
        unmockkStatic(DashRetailClient::class, DashRatesSecondFallback::class)
        db.close()
    }

    private fun freshRate(currency: String) = runBlocking {
        ExchangeRatesRepository(db.exchangeRatesDao(), mockk(relaxed = true)).getFreshExchangeRate(currency, 10_000)
    }

    @Test
    fun rowTheRefreshDidNotRenew_isNotFresh() {
        every { source.rates } returns listOf(ExchangeRate("USD", "40"))

        assertNull(freshRate("GBP"))
    }

    @Test
    fun rowTheRefreshRenewed_isFresh() {
        every { source.rates } returns listOf(ExchangeRate("USD", "40"), ExchangeRate("GBP", "30"))

        assertEquals("30", freshRate("GBP")?.rate)
    }

    @Test
    fun refreshThatFailed_leavesNoFreshRate() {
        every { source.rates } throws java.io.IOException("offline")
        every { DashRatesSecondFallback.getInstance().rates } throws java.io.IOException("offline")

        assertNull(freshRate("GBP"))
    }
}
