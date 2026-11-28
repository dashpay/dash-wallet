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

package de.schildbach.wallet.ui

import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import de.schildbach.wallet_test.R
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.ui.enter_amount.AmountView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Send and Receive request the Dash amount this view holds. Showing it in fiat, or putting a
 * saved fiat amount back after the screen is rebuilt, must never change it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
class AmountViewDashAmountTest {
    private fun amountView(dashPrice: Fiat): AmountView {
        val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.My_Theme)
        return AmountView(context, Robolectric.buildAttributeSet().build()).apply {
            exchangeRate = ExchangeRate(Coin.COIN, dashPrice)
        }
    }

    @Test
    fun switchingFromDashToFiat_keepsTheDashAmount() {
        val view = amountView(Fiat.parseFiat("JPY", "2000"))
        view.dashToFiat = true
        view.input = "0.0002"
        assertEquals(Coin.parseCoin("0.0002"), view.dashAmount)

        view.dashToFiat = false

        assertEquals(Coin.parseCoin("0.0002"), view.dashAmount)
        // less than one yen is shown as it is, not as zero
        assertEquals("0.40", view.input)
    }

    @Test
    fun savedFiatAmountPutBack_givesTheSameDashAmount() {
        // 0.01234 Dash at 100 USD is 1.234 USD, which is what the screen saves and puts back
        val view = amountView(Fiat.parseFiat("USD", "100"))

        view.input = Fiat.parseFiat("USD", "1.234").toPlainString()

        assertEquals(Coin.parseCoin("0.01234"), view.dashAmount)
    }
}
