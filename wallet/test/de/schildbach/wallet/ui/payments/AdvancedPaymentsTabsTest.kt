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

import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.ui.payments.PaymentsFragment.Companion.ACTIVE_TAB_INTERNAL
import de.schildbach.wallet.ui.payments.PaymentsFragment.Companion.ACTIVE_TAB_PAY
import de.schildbach.wallet.ui.payments.PaymentsFragment.Companion.ACTIVE_TAB_RECEIVE
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AdvancedPaymentsTabsTest {
    private val twoTabs = listOf(ACTIVE_TAB_RECEIVE, ACTIVE_TAB_PAY)
    private val threeTabs = listOf(ACTIVE_TAB_RECEIVE, ACTIVE_TAB_INTERNAL, ACTIVE_TAB_PAY)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `same gating as PaymentsFragment`() = runBlocking {
        assertEquals(threeTabs, advancedPaymentsTabsFlow(flowOf(true), supportsPlatform = true).first())
        assertEquals(twoTabs, advancedPaymentsTabsFlow(flowOf(false), supportsPlatform = true).first())
        assertEquals(twoTabs, advancedPaymentsTabsFlow(flowOf(null), supportsPlatform = true).first())
        assertEquals(twoTabs, advancedPaymentsTabsFlow(flowOf(true), supportsPlatform = false).first())
    }

    @Test
    fun `an unreadable flag hides Internal, then recovers`() = runBlocking {
        var reads = 0
        val flag = flow {
            if (reads++ == 0) throw IOException("datastore unreadable")
            emit(true)
        }
        val emitted = advancedPaymentsTabsFlow(flag, supportsPlatform = true, retryDelayMs = { 0L })
            .take(2)
            .toList()
        assertEquals(listOf(twoTabs, threeTabs), emitted)
    }

    @Test
    fun `view model state is empty until the flag is read, then follows it`() {
        val flag = MutableStateFlow<Boolean?>(false)
        val config = mockk<DashPayConfig> {
            every { observePreservingErrors(DashPayConfig.USE_KOTLIN_SDK_SHIELDED) } returns flag
        }
        val viewModel = AdvancedPaymentsViewModel(config)
        // Constants.SUPPORTS_PLATFORM decides whether a set flag can add Internal.
        val expectedWhenOn = PaymentsFragment.tabIdsFor(de.schildbach.wallet.Constants.SUPPORTS_PLATFORM)

        assertEquals(twoTabs, viewModel.uiState.value.tabIds)
        flag.value = true
        assertEquals(expectedWhenOn, viewModel.uiState.value.tabIds)
    }

    @Test
    fun `initial state has no tabs`() {
        assertNull(AdvancedPaymentsUIState().tabIds)
    }

    @Test
    fun `a tab that is not shown falls back to the first page`() {
        assertEquals(1, AdvancedPaymentsFragment.positionOf(threeTabs, ACTIVE_TAB_INTERNAL))
        assertEquals(2, AdvancedPaymentsFragment.positionOf(threeTabs, ACTIVE_TAB_PAY))
        assertEquals(1, AdvancedPaymentsFragment.positionOf(twoTabs, ACTIVE_TAB_PAY))
        assertEquals(0, AdvancedPaymentsFragment.positionOf(twoTabs, ACTIVE_TAB_INTERNAL))
    }
}
