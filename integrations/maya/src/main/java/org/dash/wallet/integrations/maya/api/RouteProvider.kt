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

package org.dash.wallet.integrations.maya.api

/**
 * The cross-chain swap provider that routes a DASH→asset swap. For assets routable
 * via a single provider this is known statically from the token-list classification;
 * for assets routable via BOTH it is resolved asynchronously by an indicative quote
 * (the SwapKit-recommended route) — see
 * [org.dash.wallet.integrations.maya.swapkit.SwapKitApiAggregator].
 */
enum class RouteProvider {
    MAYA,
    NEAR
}
