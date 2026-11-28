package de.schildbach.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletBalanceWidgetProviderTest {
    @Test
    fun `hidden balances mask both rows and keep fiat row visible`() {
        val display = WalletBalanceWidgetProvider.createBalanceDisplay(
            "0.123456",
            "≈ $ 10.00",
            true,
            "••••••"
        )

        assertEquals("••••••", display.dashBalance.toString())
        assertEquals("••••••", display.localBalance.toString())
        assertTrue(display.isLocalBalanceVisible)
    }

    @Test
    fun `visible balances preserve both formatted values`() {
        val display = WalletBalanceWidgetProvider.createBalanceDisplay(
            "0.123456",
            "≈ $ 10.00",
            false,
            "••••••"
        )

        assertEquals("0.123456", display.dashBalance.toString())
        assertEquals("≈ $ 10.00", display.localBalance.toString())
        assertTrue(display.isLocalBalanceVisible)
    }

    @Test
    fun `visible balance hides fiat row when exchange rate is unavailable`() {
        val display = WalletBalanceWidgetProvider.createBalanceDisplay(
            "0.123456",
            null,
            false,
            "••••••"
        )

        assertEquals("0.123456", display.dashBalance.toString())
        assertEquals(null, display.localBalance)
        assertFalse(display.isLocalBalanceVisible)
    }
}
