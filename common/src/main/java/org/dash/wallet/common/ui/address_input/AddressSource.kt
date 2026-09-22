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

package org.dash.wallet.common.ui.address_input

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

data class AddressSource(
    val id: String,
    @StringRes
    val name: Int,
    @DrawableRes
    val icon: Int,
    val address: String?,
    val currency: String?,
    /**
     * True when this exchange is connected but cannot hold the selected asset on the selected
     * network (its deposit address is for a different chain). The row is shown disabled with an
     * explanation instead of being silently dropped. [address] is left null in that case so the
     * wrong-network address can never be pasted.
     */
    val unsupported: Boolean = false,
    /**
     * True when the user is signed in to this exchange, regardless of whether an address was
     * obtained. Distinct from address-presence: a connected exchange whose deposit-address lookup
     * failed has [isConnected] true and [address] null, and must not be offered a "Log in" action.
     */
    val isConnected: Boolean = false
)
