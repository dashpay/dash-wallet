/*
 * Copyright 2023 Dash Core Group.
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

import com.google.gson.annotations.SerializedName

/**
 * Response from https://v6.exchangerate-api.com/v6
 */
data class ExchangeRateResponse(
    val result: String,
    @SerializedName("base_code") val baseCode: String,
    @SerializedName("time_last_update_unix") val lastUpdate: Long,
    @SerializedName("conversion_rates") val rates: Map<String, Double>
)
