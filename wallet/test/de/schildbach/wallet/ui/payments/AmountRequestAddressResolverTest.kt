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

package de.schildbach.wallet.ui.payments

import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * [ReceiveFragment] is reachable from other apps with arguments they choose, so whatever its
 * argument holds, the request it shows carries only an address read from the wallet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class AmountRequestAddressResolverTest {
    private companion object {
        const val SHIELDED_ADDRESS = "tdash1qshieldedaddress"
        const val PLATFORM_ADDRESS = "tevo1qplatformaddress"
        const val IDENTITY_ID = "GWRSAVFMjXx8HpQFaNJMqBV7MBgMK4br5UESsB4S31Ec"

        /** An address another app would like the user to show as their own. */
        const val FOREIGN_ADDRESS = "yjSvwyLB5X4dqQqVMPMu6UdrFpYZ3u9v5U"
    }

    private val reads = mutableListOf<String>()
    private var shielded: suspend () -> String? = { SHIELDED_ADDRESS }
    private var platform: suspend () -> String? = { PLATFORM_ADDRESS }
    private var identity: suspend () -> String? = { IDENTITY_ID }

    private val resolver = AmountRequestAddressResolver(
        ReceiveAddressSources(
            core = ReceiveAddressSource { reads += "core"; error("Core is read by ReceiveFragment, not here") },
            shielded = ReceiveAddressSource { reads += "shielded"; shielded() },
            platform = ReceiveAddressSource { reads += "platform"; platform() }
        ),
        identity = ReceiveAddressSource { reads += "identity"; identity() }
    )

    private fun resolve(balance: String?) = runBlocking { resolver.resolve(balance) }

    @Test
    fun `no balance is the Core flow`() {
        assertEquals(AmountRequestTarget.Core, resolve(null))
        assertEquals(AmountRequestTarget.Core, resolve(ReceiveBalanceTab.CORE.name))
        assertTrue(reads.isEmpty())
    }

    @Test
    fun `the old address arguments from another app are ignored`() {
        val external = Bundle().apply {
            putString("request_address", FOREIGN_ADDRESS)
            putInt("request_address_label", 1)
        }
        val balance = ReceiveFragmentArgs.fromBundle(external).requestBalance
        assertNull(balance)
        // So the request is Core's, whose address ReceiveFragment reads from the wallet.
        assertEquals(AmountRequestTarget.Core, resolve(balance))
    }

    @Test
    fun `each balance's address comes from its wallet source`() {
        assertEquals(
            AmountRequestTarget.Address(ReceiveBalanceTab.SHIELD, SHIELDED_ADDRESS),
            resolve(ReceiveBalanceTab.SHIELD.name)
        )
        assertEquals(
            AmountRequestTarget.Address(ReceiveBalanceTab.PLATFORM, PLATFORM_ADDRESS),
            resolve(ReceiveBalanceTab.PLATFORM.name)
        )
        assertEquals(
            AmountRequestTarget.Address(ReceiveBalanceTab.IDENTITY, IDENTITY_ID),
            resolve(ReceiveBalanceTab.IDENTITY.name)
        )
        assertEquals(listOf("shielded", "platform", "identity"), reads)
    }

    @Test
    fun `an external value that is not a balance shows no request and reads nothing`() {
        // The old argument carried an address; one passed as the balance is not one.
        for (bogus in listOf(FOREIGN_ADDRESS, "dash:$FOREIGN_ADDRESS?amount=1", "shield", "Shield", " SHIELD", "", "4")) {
            assertEquals(bogus, AmountRequestTarget.Unavailable, resolve(bogus))
        }
        assertTrue(reads.isEmpty())
    }

    @Test
    fun `a balance whose source has nothing shows no request, never Core's address`() {
        shielded = { null }
        platform = { "" }
        identity = { throw IOException("identity read failed") }
        assertEquals(AmountRequestTarget.Unavailable, resolve(ReceiveBalanceTab.SHIELD.name))
        assertEquals(AmountRequestTarget.Unavailable, resolve(ReceiveBalanceTab.PLATFORM.name))
        assertEquals(AmountRequestTarget.Unavailable, resolve(ReceiveBalanceTab.IDENTITY.name))
        assertTrue("core" !in reads)
    }
}
