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

package de.schildbach.wallet.ui.security

import androidx.fragment.app.FragmentActivity
import de.schildbach.wallet.ui.CheckPinDialog
import org.dash.wallet.common.services.SecurityModel
import javax.inject.Inject

class PinCodeRequestLauncher @Inject constructor(): SecurityModel {
    override suspend fun requestPinCode(activity: FragmentActivity): String? {
        return CheckPinDialog.showAsync(activity)
    }
}