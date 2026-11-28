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

package de.schildbach.wallet.ui.cutover

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.service.platform.sdk.SdkBindBlocker
import de.schildbach.wallet.service.platform.sdk.SdkBindRetryService
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SdkBindPendingViewModel @Inject constructor(
    private val retryService: SdkBindRetryService
) : ViewModel() {
    /** Null once the SDK wallet is bound — the sheet dismisses itself on that edge. */
    val blocker: StateFlow<SdkBindBlocker?> = retryService.blocker

    fun retryNow() = retryService.retryNowInBackground("user tapped retry")
}
