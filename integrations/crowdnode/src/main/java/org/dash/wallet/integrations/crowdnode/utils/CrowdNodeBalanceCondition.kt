/*
 * Copyright 2022 Dash Core Group.
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

package org.dash.wallet.integrations.crowdnode.utils

import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.dash.wallet.common.services.LeftoverBalanceException
import kotlin.jvm.Throws

class CrowdNodeBalanceCondition {
    @Throws(LeftoverBalanceException::class)
    fun check(
        walletBalance: Coin,
        address: Address?,
        amount: Coin,
        crowdNodeConfig: CrowdNodeConfig
    ) {
        runBlocking { // TODO: remove runBlocking when this class is used from Kotlin code only
            val crowdNodeBalance = crowdNodeConfig.get(CrowdNodeConfig.LAST_BALANCE) ?: 0
            val crowdNodeAddress = address?.let { CrowdNodeConstants.getCrowdNodeAddress(address.parameters) }

            if (crowdNodeBalance <= 0 && (address == null || address != crowdNodeAddress)) {
                // If we're sending somewhere else and CrowdNode balance is 0,
                // no need to check impediments
                return@runBlocking
            }

            val leftoverBalance = walletBalance.subtract(amount)
            val minimumLeftoverBalance = CrowdNodeConstants.MINIMUM_LEFTOVER_BALANCE

            if (leftoverBalance.isLessThan(minimumLeftoverBalance)) {
                throw LeftoverBalanceException(
                    minimumLeftoverBalance.subtract(leftoverBalance),
                    "The wallet should have at least ${minimumLeftoverBalance.toFriendlyString()} left for withdrawals"
                )
            }
        }
    }
}
