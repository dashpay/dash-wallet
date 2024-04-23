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

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.math.BigDecimal

enum class CurrencyInputType {
    Dash,
    Crypto,
    Fiat
}

/**
 Holds an amount in terms of dash, a fiat currency and another crypto currency. When exchange rates are changed
 anchor is used to determine which should be recalculated.  Dash is the default anchor currency.
 */
@Parcelize
data class Amount(
    private var _dash: BigDecimal = BigDecimal.ZERO,
    private var _fiat: BigDecimal = BigDecimal.ZERO,
    private var _crypto: BigDecimal = BigDecimal.ZERO,
    private var anchor: CurrencyInputType = CurrencyInputType.Dash,
    private var _dashFiatExchangeRate: BigDecimal = BigDecimal.ONE,
    private var _cryptoFiatExchangeRate: BigDecimal = BigDecimal.ONE,
    var dashCode: String = "DASH",
    var fiatCode: String = "USD",
    var cryptoCode: String = "BTC"
) : Parcelable {
    var dash: BigDecimal
        get() = _dash
        set(value) {
            _dash = value
            anchor = CurrencyInputType.Dash
            update()
        }
    var fiat: BigDecimal
        get() = _fiat
        set(value) {
            _fiat = value
            anchor = CurrencyInputType.Fiat
            update()
        }
    var crypto: BigDecimal
        get() = _crypto
        set(value) {
            _crypto = value
            anchor = CurrencyInputType.Crypto
            update()
        }

    val anchoredValue: BigDecimal
        get() {
            return when (anchor) {
                CurrencyInputType.Dash -> _dash
                CurrencyInputType.Fiat -> _fiat
                CurrencyInputType.Crypto -> _crypto
            }
        }

    val anchoredCurrencyCode: String
        get() {
            return when (anchor) {
                CurrencyInputType.Dash -> dashCode
                CurrencyInputType.Fiat -> fiatCode
                CurrencyInputType.Crypto -> cryptoCode
            }
        }

    var anchoredType: CurrencyInputType
        get() = anchor
        set(value) {
            anchor = value
        }

    /** 1 DASH = x Fiat, eg 1 DASH = $35.87 or $35.87/DASH */
    var dashFiatExchangeRate: BigDecimal
        get() = _dashFiatExchangeRate
        set(value) {
            _dashFiatExchangeRate = value
            update()
        }

    /** updates the other currencies based on the current #[anchor] */
    private fun update() {
        when (anchor) {
            CurrencyInputType.Dash -> {
                _fiat = _dash * dashFiatExchangeRate
                _crypto = _dash * cryptoDashExchangeRate
            }

            CurrencyInputType.Fiat -> {
                _dash = _fiat / dashFiatExchangeRate
                _crypto = _fiat / cryptoFiatExchangeRate
            }

            CurrencyInputType.Crypto -> {
                _dash = _crypto / cryptoDashExchangeRate
                _fiat = _crypto * cryptoFiatExchangeRate
            }
        }
    }

    fun getValue(anchor: CurrencyInputType) = when (anchor) {
        CurrencyInputType.Dash -> _dash
        CurrencyInputType.Fiat -> _fiat
        CurrencyInputType.Crypto -> _crypto
    }

    fun setAnchoredType(currencyCode: String) {
        when (currencyCode) {
            dashCode -> anchor = CurrencyInputType.Dash
            fiatCode -> anchor = CurrencyInputType.Fiat
            cryptoCode -> anchor = CurrencyInputType.Crypto
        }
    }

    /** 1 Crypto = x Fiat, eg 1 BTC = $65,000 or $65,000/BTC */
    var cryptoFiatExchangeRate: BigDecimal
        get() = _cryptoFiatExchangeRate
        set(value) {
            _cryptoFiatExchangeRate = value
            update()
        }

    /** exchange rate between Dash and Crypto, e.g. 0.00055 DASH/BTC */
    val cryptoDashExchangeRate
        get() = dashFiatExchangeRate / cryptoFiatExchangeRate
}
