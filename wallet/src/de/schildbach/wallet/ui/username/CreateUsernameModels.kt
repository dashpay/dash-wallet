/*
 * Copyright 2024 Dash Core Group
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

package de.schildbach.wallet.ui.username

import android.os.Parcelable
import de.schildbach.wallet.data.InvitationLinkData
import kotlinx.parcelize.Parcelize

enum class CreateUsernameActions {
    CREATE_NEW,
    DISPLAY_COMPLETE,
    REUSE_TRANSACTION,
    FROM_INVITE,
    FROM_INVITE_REUSE_TRANSACTION,
}

@Parcelize
data class CreateUsernameArgs(
    val actions: CreateUsernameActions? = null,
    val userName: String? = null,
    val invite: InvitationLinkData? = null,
    val fromOnboardng: Boolean = false,
) : Parcelable