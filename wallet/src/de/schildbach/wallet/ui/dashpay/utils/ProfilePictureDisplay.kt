/*
 * Copyright 2022 Dash Core Group.
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

package de.schildbach.wallet.ui.dashpay.utils

import android.view.View
import android.widget.ImageView
import de.schildbach.wallet.database.entity.DashPayProfile
import org.dash.wallet.common.ui.avatar.ProfilePictureDisplay

fun ProfilePictureDisplay.Companion.display(
    avatarView: ImageView,
    dashPayProfile: DashPayProfile?,
    hideIfProfileNull: Boolean = false
) {
    display(avatarView, dashPayProfile, hideIfProfileNull, false, null)
}

fun ProfilePictureDisplay.Companion.display(
    avatarView: ImageView,
    dashPayProfile: DashPayProfile?,
    hideIfProfileNull: Boolean = false,
    disableTransition: Boolean,
    listener: ProfilePictureDisplay.OnResourceReadyListener?
) {
    if (dashPayProfile != null) {
        avatarView.visibility = View.VISIBLE
        display(
            avatarView,
            dashPayProfile.avatarUrl,
            dashPayProfile.avatarHash,
            dashPayProfile.username,
            disableTransition,
            listener
        )
    } else if (hideIfProfileNull) {
        avatarView.visibility = View.GONE
    }
}
