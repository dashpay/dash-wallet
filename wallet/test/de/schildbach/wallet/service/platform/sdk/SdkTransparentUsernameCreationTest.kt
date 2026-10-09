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

package de.schildbach.wallet.service.platform.sdk

import de.schildbach.wallet.Constants
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Host-JVM tests for [transparentUsernameFeeDuffs] — the exact `feeDuffs`
 * lambda [SdkTransparentUsernameCreation]'s `@Inject` constructor wires —
 * pinning the MO-1069 protocol-version gate: a wallet funded with the
 * Platform v4.2 (0.15 DASH) contested fee while the network still runs
 * protocol 13 fails DPNS registration with "Insufficient identity balance"
 * (the network still needs ~0.2 DASH of prefunded voting balance, i.e. the
 * 0.25 DASH total LEGACY fee) — reproduced live before this fix routed the
 * funding amount through [ContestedUsernameFees] instead of the raw,
 * un-gated `Constants.DASH_PAY_FEE_CONTESTED` constant.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SdkTransparentUsernameCreationTest {

    private fun sdkService(protocolVersion: Int?) = mockk<DashSdkService> {
        coEvery { currentProtocolVersion() } returns protocolVersion
    }

    @Test
    fun `contested registration funds 0_25 DASH while the network still runs protocol 13`() = runTest {
        val amountDuffs = transparentUsernameFeeDuffs(contested = true, sdkService = sdkService(13))
        assertEquals(Constants.DASH_PAY_FEE_CONTESTED_LEGACY.value, amountDuffs)
    }

    @Test
    fun `contested registration funds 0_15 DASH once the network reports protocol 14`() = runTest {
        val amountDuffs = transparentUsernameFeeDuffs(contested = true, sdkService = sdkService(14))
        assertEquals(Constants.DASH_PAY_FEE_CONTESTED.value, amountDuffs)
    }

    @Test
    fun `an unresolved protocol version refuses to fund rather than falling back to a fee`() = runTest {
        // A lower successful quote followed by a failed funding-time lookup must
        // not become a higher actual funding; the service turns the throw into a
        // NotBroadcast the user can retry.
        assertThrows(ProtocolVersionUnavailableException::class.java) {
            runBlocking { transparentUsernameFeeDuffs(contested = true, sdkService = sdkService(null)) }
        }
    }

    @Test
    fun `non-contested registration always funds the fixed 0_03 DASH fee, regardless of protocol version`() = runTest {
        assertEquals(
            Constants.DASH_PAY_FEE.value,
            transparentUsernameFeeDuffs(contested = false, sdkService = sdkService(13))
        )
        assertEquals(
            Constants.DASH_PAY_FEE.value,
            transparentUsernameFeeDuffs(contested = false, sdkService = sdkService(14))
        )
    }
}
