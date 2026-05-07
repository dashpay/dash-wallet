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

package org.dash.wallet.integrations.maya.swapkit

object SwapKitConstants {
    const val BASE_URL = "https://api.swapkit.dev/"

    const val DASH_ASSET = "DASH.DASH"

    /**
     * Provider that handles DASH-as-source through SwapKit. Maya is the only routing
     * provider currently supporting DASH; both the streaming and non-streaming variants
     * are accepted. Listing both lets SwapKit pick the better fit per swap size.
     */
    val DASH_SUPPORTED_PROVIDERS = listOf("MAYACHAIN", "MAYACHAIN_STREAMING")

    /** Default slippage (percent) for indicative quotes. */
    const val DEFAULT_SLIPPAGE_PERCENT = 3

    /**
     * SwapKit API key. Required for every request. Empty by default — must be
     * supplied via build config / remote config / service.properties before the
     * SwapKit backend can be used. The Hilt switch falls back to Maya if this
     * is blank, so the app remains functional without a key.
     */
    const val API_KEY: String = ""
}