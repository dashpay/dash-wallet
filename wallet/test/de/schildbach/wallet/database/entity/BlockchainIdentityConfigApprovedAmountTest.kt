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
import de.schildbach.wallet.service.platform.PlatformService
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.WalletDataProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Host-JVM (Robolectric) tests proving [BlockchainIdentityConfig.APPROVED_FUNDING_AMOUNT_DUFFS]
 * actually survives a process death, not just an in-memory field: a SEPARATE
 * [BlockchainIdentityConfig] instance — standing in for the fresh object
 * [CreateIdentityService.onStartCommand]'s null-intent restart constructs
 * after the process is killed — reads back the value a PRIOR instance wrote,
 * because both share the same on-disk DataStore file (MO-1069 review
 * 5447932359: the previous fix only remembered the approval in an in-memory
 * ViewModel field, which a process restart loses).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class BlockchainIdentityConfigApprovedAmountTest {

    private fun newConfig(): BlockchainIdentityConfig {
        val context = ApplicationProvider.getApplicationContext<Application>()
        return BlockchainIdentityConfig(
            context,
            mockk<WalletDataProvider>(relaxed = true),
            mockk<PlatformService>(relaxed = true)
        )
    }

    @Test
    fun approvedAmount_survivesAFreshInstance_sameAsAProcessRestart() = runBlocking {
        // "Before" process: the confirm sheet approval is persisted alongside
        // the request.
        val beforeDeath = newConfig()
        beforeDeath.insert(
            BlockchainIdentityData(
                IdentityCreationState.CREDIT_FUNDING_TX_CREATING,
                null,
                "brian",
                null,
                null,
                false,
                approvedFundingAmountDuffs = 15_000_000L
            )
        )

        // "After" process: a BRAND NEW instance (the real object identity a
        // restarted service/process would construct) backed by the SAME
        // DataStore file — no in-memory state is shared with beforeDeath.
        val afterRestart = newConfig()

        assertEquals(15_000_000L, afterRestart.loadBase().approvedFundingAmountDuffs)
        assertEquals(15_000_000L, afterRestart.load()?.approvedFundingAmountDuffs)
    }

    @Test
    fun noApproval_everPersisted_loadsAsNull() = runBlocking {
        val config = newConfig()
        config.insert(
            BlockchainIdentityData(
                IdentityCreationState.CREDIT_FUNDING_TX_CREATING,
                null,
                "brian",
                null,
                null,
                false
                // approvedFundingAmountDuffs left at its null default —
                // a record from before this field existed, or a request that
                // never recorded a confirm-sheet amount.
            )
        )

        // A retry/restart reading this record must see null, not a
        // leftover/synthesized cap — the dispatch layer then refuses to fund
        // and asks the user to reconfirm, rather than defaulting to unbounded.
        assertNull(newConfig().loadBase().approvedFundingAmountDuffs)
    }
}
