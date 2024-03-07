/*
 * Copyright 2024 Dash Core Group.
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

package org.dash.wallet.integrations.maya.model

import java.math.BigDecimal

/**
 holds an amount in terms of dash, a fiat currency and another crypto currency. When exchange rates are changed
 dash is used as the anchor to recalculate the fiat and crypto values.
 */
data class Amount(
    private var _dash: BigDecimal = BigDecimal.ZERO,
    private var _fiat: BigDecimal = BigDecimal.ZERO,
    private var _crypto: BigDecimal = BigDecimal.ZERO
) {
    var dash: BigDecimal
        get() = _dash
        set(value) {
            _dash = value
            _fiat = value * dashFiatExchangeRate
            _crypto = value * cryptoDashExchangeRate
        }
    var fiat: BigDecimal
        get() = _fiat
        set(value) {
            _fiat = value
            _dash = value / dashFiatExchangeRate
            _crypto = value / cryptoFiatExchangeRate
        }
    var crypto: BigDecimal
        get() = _crypto
        set(value) {
            _crypto = value
            _dash = value * cryptoDashExchangeRate
            _fiat = value * cryptoFiatExchangeRate
        }
    var dashFiatExchangeRate: BigDecimal = BigDecimal.ONE // 1 DASH = x Fiat, eg 1 DASH = $35.87
        set(value) {
            field = value
            _fiat = _dash / value
            _crypto = _dash / cryptoDashExchangeRate
        }
    var cryptoFiatExchangeRate: BigDecimal = BigDecimal.ONE // 1 Crypto = x Fiat
        set(value) {
            field = value
            _fiat = _dash / value
            _crypto = _dash / cryptoDashExchangeRate
        }
    val cryptoDashExchangeRate
        get() = dashFiatExchangeRate / cryptoFiatExchangeRate
}
