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

package org.dash.wallet.common.integrations

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
// import kotlinx.coroutines.flow.Flow

data class ExchangeIntegration(
    val id: String,
    val isConnected: Boolean,
    val address: String?,
    val currency: String?,
    @StringRes
    val name: Int,
    @DrawableRes
    val iconId: Int
)

interface ExchangeIntegrationProvider {
    suspend fun getDepositAddresses(currency: String): List<ExchangeIntegration>
}
