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

import de.schildbach.wallet.Constants
import de.schildbach.wallet.data.WalletData
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * Hilt constructs [PlatformServiceImplementation] while it injects
 * WalletApplication, so construction must not load native code: in 12.0.2 it
 * created the Platform (and dlopen'ed libsdklib.so) right there, and an install
 * without its native libraries died in Application.onCreate before any UI.
 *
 * A plain JVM has no libsdklib.so, so loading it here fails the same way it did
 * on those devices.
 */
class PlatformServiceConstructionTest {

    private var savedSupportsPlatform = false

    @Before
    fun setUp() {
        // False under a JVM test (no 64-bit Android ABI); without forcing it the
        // platform is never created and the test would pass vacuously.
        savedSupportsPlatform = Constants.SUPPORTS_PLATFORM
        Constants.SUPPORTS_PLATFORM = true
    }

    @After
    fun tearDown() {
        Constants.SUPPORTS_PLATFORM = savedSupportsPlatform
    }

    @Test
    fun construction_loadsNoNativeCode_firstUseDoes() {
        val service = PlatformServiceImplementation(mockk<WalletData>(relaxed = true))

        // The native load happens on first use, and only there.
        assertThrows(UnsatisfiedLinkError::class.java) { service.platform }
    }
}
