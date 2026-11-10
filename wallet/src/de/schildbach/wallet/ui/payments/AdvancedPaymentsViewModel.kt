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
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.dash.wallet.common.util.readRetryDelayMs
import org.dash.wallet.common.util.retryReadErrors
import org.slf4j.LoggerFactory
import javax.inject.Inject

/**
 * @property tabIds the logical tab ids in display order (see [PaymentsFragment.tabIdsFor]),
 *   or null until the shielded flag has been read: the screen builds no pages until then.
 */
data class AdvancedPaymentsUIState(
    val tabIds: List<Int>? = null
)

@HiltViewModel
class AdvancedPaymentsViewModel @Inject constructor(
    dashPayConfig: DashPayConfig
) : ViewModel() {
    companion object {
        private val log = LoggerFactory.getLogger(AdvancedPaymentsViewModel::class.java)
    }

    private val _uiState = MutableStateFlow(AdvancedPaymentsUIState())
    val uiState: StateFlow<AdvancedPaymentsUIState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            advancedPaymentsTabsFlow(
                shieldedFlag = dashPayConfig.observePreservingErrors(DashPayConfig.USE_KOTLIN_SDK_SHIELDED),
                supportsPlatform = Constants.SUPPORTS_PLATFORM,
                onError = { e, attempt -> log.warn("shielded flag read failed (attempt {})", attempt, e) }
            ).collect { tabIds ->
                _uiState.update { it.copy(tabIds = tabIds) }
            }
        }
    }
}

/**
 * The shielded flag as a never-failing flow: a failed read counts as off (as
 * [DashPayConfig.get] reading absent does for [PaymentsFragment]), is reported to
 * [onError] and is retried, so the flow never completes on an [Exception] (see
 * [retryReadErrors]: a cancellation or an [Error] still propagates). Shared by the
 * Advanced payments tabs ([advancedPaymentsTabsFlow]) and the Receive balance tabs.
 */
internal fun shieldedEnabledFlow(
    shieldedFlag: Flow<Boolean?>,
    onError: (Throwable, Long) -> Unit = { _, _ -> },
    retryDelayMs: (Long) -> Long = ::readRetryDelayMs
): Flow<Boolean> = shieldedFlag
    .map { it == true }
    .retryReadErrors(false, retryDelayMs, onError)
    .distinctUntilChanged()

/**
 * The tab set for the Advanced payments screen. Same gating as [PaymentsFragment]: the
 * "Internal" tab needs `SUPPORTS_PLATFORM` and `USE_KOTLIN_SDK_SHIELDED`; a failed read
 * hides it (see [shieldedEnabledFlow]).
 */
internal fun advancedPaymentsTabsFlow(
    shieldedFlag: Flow<Boolean?>,
    supportsPlatform: Boolean,
    onError: (Throwable, Long) -> Unit = { _, _ -> },
    retryDelayMs: (Long) -> Long = ::readRetryDelayMs
): Flow<List<Int>> = shieldedEnabledFlow(shieldedFlag, onError, retryDelayMs)
    .map { PaymentsFragment.tabIdsFor(supportsPlatform && it) }
    .distinctUntilChanged()
