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

package de.schildbach.wallet.service.platform

import org.bitcoinj.core.Coin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ContestedUsernameFeesTest {

    @After
    fun tearDown() {
        ContestedUsernameFees.reset()
    }

    @Test
    fun unknownProtocolVersion_usesTheHigherPreV14Fees() {
        assertEquals(0, ContestedUsernameFees.currentProtocolVersion)
        assertEquals(Coin.parseCoin("0.25"), ContestedUsernameFees.fee)
        assertEquals(Coin.parseCoin("0.20"), ContestedUsernameFees.nameFee)
    }

    @Test
    fun protocolVersion13_usesPreV14Fees() {
        ContestedUsernameFees.updateProtocolVersion(13)
        assertEquals(Coin.parseCoin("0.25"), ContestedUsernameFees.fee)
        assertEquals(Coin.parseCoin("0.20"), ContestedUsernameFees.nameFee)
    }

    @Test
    fun protocolVersion14_usesReducedFees() {
        ContestedUsernameFees.updateProtocolVersion(14)
        assertEquals(Coin.parseCoin("0.15"), ContestedUsernameFees.fee)
        assertEquals(Coin.parseCoin("0.10"), ContestedUsernameFees.nameFee)
    }

    @Test
    fun laterProtocolVersions_keepTheReducedFees() {
        assertEquals(Coin.parseCoin("0.15"), ContestedUsernameFees.feeFor(15))
        assertEquals(Coin.parseCoin("0.10"), ContestedUsernameFees.nameFeeFor(15))
    }

    @Test
    fun protocolVersion_neverGoesDown() {
        ContestedUsernameFees.updateProtocolVersion(14)
        ContestedUsernameFees.updateProtocolVersion(13)
        assertEquals(14, ContestedUsernameFees.currentProtocolVersion)
        assertEquals(Coin.parseCoin("0.15"), ContestedUsernameFees.fee)
    }
}
