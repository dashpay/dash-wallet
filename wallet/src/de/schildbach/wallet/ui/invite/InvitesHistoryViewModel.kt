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

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.database.dao.InvitationsDaoAsync
import org.dash.wallet.common.data.SingleLiveEvent
import org.slf4j.LoggerFactory
import javax.inject.Inject

@HiltViewModel
class InvitesHistoryViewModel @Inject constructor(
    invitationsDaoAsync: InvitationsDaoAsync
) : ViewModel() {
    companion object {
        val log = LoggerFactory.getLogger(InvitesHistoryViewModel::class.java)
    }

    val invitationHistory = invitationsDaoAsync.loadAll()

    enum class Filter {
        ALL,
        CLAIMED,
        PENDING
    }

    val filter = Filter.ALL

    val filterClick = SingleLiveEvent<Filter>()
}
