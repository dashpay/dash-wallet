/*
 * Copyright 2024 Dash Core Group.
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

package org.dash.wallet.integrations.maya.ui

import org.dash.wallet.common.ui.address_input.AddressInputFragment
import org.dash.wallet.common.util.safeNavigate

class MayaAddressInputFragment : AddressInputFragment() {
    override fun continueAction() {
        safeNavigate(
            MayaAddressInputFragmentDirections.mayaAddressInputToEnterAmount(
                viewModel.currency,
                viewModel.addressResult.paymentIntent!!
            )
        )
        // TODO: add event monitoring here
        // viewModel.logEvent(AnalyticsConstants.AddressInput.CONTINUE)
    }
}
