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

import org.dash.wallet.integrations.maya.BuildConfig

object SwapKitConstants {
    const val BASE_URL = "https://api.swapkit.dev/"

    const val DASH_ASSET = "DASH.DASH"

    /** Default slippage (percent) for indicative quotes. */
    const val DEFAULT_SLIPPAGE_PERCENT = 2

    /**
     * The only two providers that route DASH (verified from `/providers`:
     * the sole entries whose `supportedChainIds` contain `dash`). An asset is
     * "Maya-only" when MAYACHAIN can route it but NEAR cannot — see
     * SWAPKIT_PROTOCOL.md → "Detecting Maya-only Assets".
     */
    const val NEAR_PROVIDER = "NEAR"

    /** MAYACHAIN non-streaming returns no token list; only the streaming variant does. */
    const val MAYACHAIN_PROVIDER = "MAYACHAIN_STREAMING"

    /**
     * SwapKit API key, sourced from `service.properties` (SWAPKIT_API_KEY) at build
     * time via Maya's BuildConfig. Blank when the property is absent — the Hilt
     * switch falls back to Maya in that case, so the app remains functional without
     * a key.
     */
    const val API_KEY: String = BuildConfig.SWAPKIT_API_KEY
}
