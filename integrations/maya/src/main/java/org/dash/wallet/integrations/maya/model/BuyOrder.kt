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

package org.dash.wallet.integrations.maya.model

import java.math.BigDecimal

/**
 * Result of creating a BUY order (crypto -> DASH) via
 * [org.dash.wallet.integrations.maya.api.SwapProvider.createBuyOrder].
 *
 * [depositAddress] is the SwapKit inbound address the user must send [sellAmount] of
 * [sellAsset] to. [memo] is an optional chain memo/tag that some chains require alongside the
 * deposit (null for most UTXO chains). [expectedDashAmount] is the DASH the swap is expected to
 * deliver, as a human-unit decimal.
 */
data class BuyOrder(
    val depositAddress: String,
    val memo: String?,
    val expectedDashAmount: BigDecimal,
    val sellAsset: String,
    val sellAmount: String
)