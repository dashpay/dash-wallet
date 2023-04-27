/*
 * Copyright 2021 Dash Core Group.
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

package de.schildbach.wallet.ui.invite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.database.dao.InvitationsDao
import de.schildbach.wallet.ui.util.SingleLiveEvent
import org.slf4j.LoggerFactory
import javax.inject.Inject

@HiltViewModel
class InvitesHistoryViewModel @Inject constructor(
    invitationsDao: InvitationsDao
) : ViewModel() {
    companion object {
        val log = LoggerFactory.getLogger(InvitesHistoryViewModel::class.java)
    }

    val invitationHistory = invitationsDao.observe().asLiveData()

    enum class Filter {
        ALL,
        CLAIMED,
        PENDING
    }

    val filter = Filter.ALL

    val filterClick = SingleLiveEvent<Filter>()
}
