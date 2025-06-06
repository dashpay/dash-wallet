/*
 * Copyright 2021 Dash Core Group.
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
package org.dash.wallet.features.exploredash.utils

object CTXSpendConstants {
    const val BASE_URL = "https://spend.ctx.com/"
    const val CLIENT_ID_PARAM_NAME = "X-Client-Id"
    @JvmField var CLIENT_ID = "dcg_android"
    const val DEFAULT_DISCOUNT: Int = 0 // 0%
    const val DEFAULT_DISCOUNT_AS_DOUBLE: Double = 0.0 // 0%
    const val REPORT_EMAIL = "support@ctx.com"
}
