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
package de.schildbach.wallet.database.entity

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import de.schildbach.wallet.service.platform.VerifyLinkObligation
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The durable verification-link obligation over the real DataStore-backed [BlockchainIdentityConfig]. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class VerifyLinkObligationStoreTest {
    private val identityId = "6XqBkTZTUnDMcgGvKzs5NRotZbAMBjKhJ4bQzKzXcCwr"
    private val link = "https://example.com/proof"

    private fun config() = BlockchainIdentityConfig(
        ApplicationProvider.getApplicationContext(),
        mockk(relaxed = true),
        mockk(relaxed = true)
    ).also { runBlocking { it.clear() } }

    @Test
    fun `an obligation survives until the same request clears it`() = runBlocking {
        val config = config()
        config.recordVerifyObligation(identityId, "alice", link)
        config.markVerifyUncertain(identityId, "Alice", 42L)
        // the same request recorded again (a retry of the registration) keeps its uncertainty
        config.recordVerifyObligation(identityId, "alice", link)

        val pending = config.verifyObligation()
        assertEquals(VerifyLinkObligation(identityId, "alice", link, 42L, uncertain = true), pending)

        config.clearVerifyObligation(VerifyLinkObligation(identityId, "bobby", link))
        assertEquals("another request's clear changes nothing", pending, config.verifyObligation())

        config.clearVerifyObligation(requireNotNull(pending))
        assertNull(config.verifyObligation())
    }

    @Test
    fun `a new request replaces the old one and its bookkeeping`() = runBlocking {
        val config = config()
        config.recordVerifyObligation(identityId, "alice", link)
        config.markVerifyAttempt(VerifyLinkObligation(identityId, "alice", link), 7L)
        config.recordVerifyObligation(identityId, "bobby", link)

        assertEquals(VerifyLinkObligation(identityId, "bobby", link), config.verifyObligation())
    }
}
