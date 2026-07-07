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

package org.dash.wallet.common.ui.enter_amount

import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.dash.wallet.common.money.FiatValue
import org.dash.wallet.common.money.toFiat

/**
 * Neutral counterpart of [AmountView.exchangeRate] for modules that must not depend on dashj:
 * sets the view's conversion rate from the fiat [price] of one Dash (null clears the rate).
 * Mirrors `exchangeRate = ExchangeRate(Coin.COIN, price)`.
 */
fun AmountView.setDashPrice(price: FiatValue?) {
    exchangeRate = price?.let { ExchangeRate(Coin.COIN, it.toFiat()) }
}
