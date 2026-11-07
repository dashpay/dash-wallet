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

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import org.dash.wallet.common.util.readRetryDelayMs
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import javax.inject.Inject

/**
 * @property tabIds the logical tab ids in display order (see [advancedPaymentsTabIds]); null only
 *   before the first state is set (the screen builds no pages until then).
 */
data class AdvancedPaymentsUIState(
    val tabIds: List<Int>? = null
)

@HiltViewModel
class AdvancedPaymentsViewModel internal constructor(supportsPlatform: Boolean) : ViewModel() {
    @Inject
    constructor() : this(Constants.SUPPORTS_PLATFORM)

    private val _uiState = MutableStateFlow(AdvancedPaymentsUIState(tabIds = advancedPaymentsTabIds(supportsPlatform)))
    val uiState: StateFlow<AdvancedPaymentsUIState> = _uiState.asStateFlow()
}

/**
 * The tab set for the Advanced payments screen (MO-1125): the "Internal" tab is always shown
 * where Platform is supported, whatever the shielded flag — with the flag off, the Internal
 * transfer shows Shielded disabled. Basic mode ([PaymentsFragment]) keeps its own gating.
 */
internal fun advancedPaymentsTabIds(supportsPlatform: Boolean): List<Int> = PaymentsFragment.tabIdsFor(supportsPlatform)

/**
 * The shielded flag as a never-failing flow: a failed read counts as off (as
 * [DashPayConfig.get] reading absent does for [PaymentsFragment]), is reported to
 * [onError] and is retried, so the flow never completes on an error. Used by the Receive
 * balance tabs.
 */
internal fun shieldedEnabledFlow(
    shieldedFlag: Flow<Boolean?>,
    onError: (Throwable, Long) -> Unit = { _, _ -> },
    retryDelayMs: (Long) -> Long = ::readRetryDelayMs
): Flow<Boolean> = shieldedFlag
    .map { it == true }
    .retryWhen { e, attempt ->
        onError(e, attempt)
        emit(false)
        delay(retryDelayMs(attempt))
        true
    }
    .distinctUntilChanged()
