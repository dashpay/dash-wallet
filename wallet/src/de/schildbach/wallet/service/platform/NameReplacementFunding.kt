/*
 * Copyright 2026 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.service.platform

import de.schildbach.wallet.Constants
import de.schildbach.wallet.data.CreditBalanceInfo
import de.schildbach.wallet.data.CreditBalanceInfo.Companion.CREDITS_PER_DUFF
import org.bitcoinj.core.Coin

object NameReplacementFunding {
    private const val NAME_REGISTRATION_CREDITS = CreditBalanceInfo.MAX_OPERATION_COST * 2
    private const val TOP_UP_STEP_DUFFS = 100_000L

    fun creditsNeeded(
        contestable: Boolean,
        protocolVersion: Int = ContestedUsernameFees.currentProtocolVersion
    ): Long = if (contestable) {
        ContestedUsernameFees.nameFeeFor(protocolVersion).value * CREDITS_PER_DUFF + NAME_REGISTRATION_CREDITS
    } else {
        NAME_REGISTRATION_CREDITS
    }

    fun creditsCover(
        credits: Long,
        contestable: Boolean,
        protocolVersion: Int = ContestedUsernameFees.currentProtocolVersion
    ): Boolean = credits >= creditsNeeded(contestable, protocolVersion)

    /** The wallet top-up that, with [credits], pays for the name; zero when the credits already do. */
    fun topUp(
        credits: Long,
        contestable: Boolean,
        protocolVersion: Int = ContestedUsernameFees.currentProtocolVersion
    ): Coin {
        val shortfall = creditsNeeded(contestable, protocolVersion) - credits
        if (shortfall <= 0) {
            return Coin.ZERO
        }
        val stepCredits = TOP_UP_STEP_DUFFS * CREDITS_PER_DUFF
        val steps = (shortfall + stepCredits - 1) / stepCredits
        val minimum = if (contestable) ContestedUsernameFees.nameFeeFor(protocolVersion) else Constants.DASH_PAY_FEE
        return maxOf(Coin.valueOf(steps * TOP_UP_STEP_DUFFS), minimum)
    }
}
