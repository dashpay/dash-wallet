/*
 * Copyright 2021 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui.invite

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import javax.inject.Inject

@HiltViewModel
class InvitesHistoryFilterViewModel @Inject constructor(
    application: Application,
    private val analytics: AnalyticsService
) : AndroidViewModel(application) {
    val filterBy = SingleLiveEvent<InvitesHistoryViewModel.Filter>()

    fun setFilter(value: InvitesHistoryViewModel.Filter) {
        if (filterBy.value != value) {
            filterBy.postValue(value)
            analytics.logEvent(
                AnalyticsConstants.Invites.HISTORY_FILTER,
                mapOf(AnalyticsConstants.Parameter.VALUE to value.name)
            )
        }
    }
}
