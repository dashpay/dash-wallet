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

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The two resolvers differ only in what an unknown protocol version means:
 * [ContestedUsernameFees.current] (quotes) shows the legacy figure,
 * [ContestedUsernameFees.resolved] (funding) refuses — so a quote at
 * protocol 14 can never be followed by a legacy-priced funding when the
 * second lookup fails.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContestedUsernameFeesTest {

    private fun sdkService(protocolVersion: Int?) = mockk<DashSdkService> {
        coEvery { currentProtocolVersion() } returns protocolVersion
    }

    @Test
    fun `protocol 14 resolves the current fees for quotes and funding alike`() = runTest {
        assertEquals(ContestedUsernameFees.CURRENT, ContestedUsernameFees.current(sdkService(14)))
        assertEquals(ContestedUsernameFees.CURRENT, ContestedUsernameFees.resolved(sdkService(14)))
    }

    @Test
    fun `protocol 13 resolves the legacy fees for quotes and funding alike`() = runTest {
        assertEquals(ContestedUsernameFees.LEGACY, ContestedUsernameFees.current(sdkService(13)))
        assertEquals(ContestedUsernameFees.LEGACY, ContestedUsernameFees.resolved(sdkService(13)))
    }

    @Test
    fun `an unknown protocol version quotes the legacy fees`() = runTest {
        assertEquals(ContestedUsernameFees.LEGACY, ContestedUsernameFees.current(sdkService(null)))
    }

    @Test
    fun `an unknown protocol version refuses to resolve a funding fee`() {
        assertThrows(ProtocolVersionUnavailableException::class.java) {
            runBlocking { ContestedUsernameFees.resolved(sdkService(null)) }
        }
    }
}
