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

package org.dash.wallet.integrations.crowdnode

import junit.framework.TestCase.assertTrue
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.params.TestNet3Params
import org.dash.wallet.common.services.LeftoverBalanceException
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeBalanceCondition
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConfig
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConstants
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class CrowdNodeBalanceConditionTest {
    private val params = TestNet3Params.get()

    @Test
    fun check_zeroBalance_passes() {
        val crowdNodeConfig = mock<CrowdNodeConfig> {
            onBlocking { get(CrowdNodeConfig.LAST_BALANCE) } doReturn 0
        }
        CrowdNodeBalanceCondition().check(
            Coin.COIN,
            Address.fromBase58(params, "yihMSMoesHX1JhbntTiV5Nptf5NLrmFMCu"),
            Coin.COIN,
            crowdNodeConfig
        )
        assertTrue(true) // No exceptions
    }

    @Test
    fun check_nonZeroBalance_throws() {
        val crowdNodeConfig = mock<CrowdNodeConfig> {
            onBlocking { get(CrowdNodeConfig.LAST_BALANCE) } doReturn 5000
        }

        val thrown: LeftoverBalanceException = assertThrows(
            "Expected check() to throw, but it didn't",
            LeftoverBalanceException::class.java
        ) {
            CrowdNodeBalanceCondition().check(
                Coin.COIN,
                Address.fromBase58(params, "yihMSMoesHX1JhbntTiV5Nptf5NLrmFMCu"),
                Coin.COIN - Coin.valueOf(20000),
                crowdNodeConfig
            )
        }
        assertTrue(thrown.message?.contains(CrowdNodeConstants.MINIMUM_LEFTOVER_BALANCE.toFriendlyString()) == true)
    }

    @Test
    fun check_zeroBalance_sendingToCrowdNode_throws() {
        val crowdNodeConfig = mock<CrowdNodeConfig> {
            onBlocking { get(CrowdNodeConfig.LAST_BALANCE) } doReturn 0
        }

        val thrown: LeftoverBalanceException = assertThrows(
            "Expected check() to throw, but it didn't",
            LeftoverBalanceException::class.java
        ) {
            CrowdNodeBalanceCondition().check(
                Coin.COIN,
                CrowdNodeConstants.getCrowdNodeAddress(params),
                Coin.COIN,
                crowdNodeConfig
            )
        }
        assertTrue(thrown.message?.contains(CrowdNodeConstants.MINIMUM_LEFTOVER_BALANCE.toFriendlyString()) == true)
    }

    @Test
    fun check_unknownAddress_zeroBalance_passes() {
        val crowdNodeConfig = mock<CrowdNodeConfig> {
            onBlocking { get(CrowdNodeConfig.LAST_BALANCE) } doReturn 0
        }
        CrowdNodeBalanceCondition().check(
            Coin.COIN,
            null,
            Coin.COIN,
            crowdNodeConfig
        )
        assertTrue(true) // No exceptions
    }
}
