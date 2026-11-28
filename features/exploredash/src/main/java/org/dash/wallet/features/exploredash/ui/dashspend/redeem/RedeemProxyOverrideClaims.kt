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
package org.dash.wallet.features.exploredash.ui.dashspend.redeem

class RedeemProxyOverrideClaims {
    private val holders = mutableSetOf<Any>()

    val isEmpty: Boolean
        get() = holders.isEmpty()

    fun claim(holder: Any) {
        holders.add(holder)
    }

    // True only for the last holder to let go, which is when the shared override has to be lifted.
    fun release(holder: Any): Boolean {
        return holders.remove(holder) && holders.isEmpty()
    }
}
