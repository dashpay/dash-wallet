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

package org.dash.wallet.integrations.maya.utils

/**
 * The cross-chain swap operation the user picked on the DashDEX portal.
 *
 * - [SELL]: Dash Wallet -> any crypto. Supported by both Maya and SwapKit.
 * - [BUY]: any crypto -> Dash Wallet. Only reachable on the SwapKit backend
 *   (see [SwapBackend.supportsBuy]); Maya cannot buy Dash. Assets routable from DASH
 *   exclusively via MAYACHAIN (`PoolInfo.mayaOnly`) have no buy route and are hidden
 *   from the coin picker when buying.
 */
enum class SwapDirection {
    BUY,
    SELL
}