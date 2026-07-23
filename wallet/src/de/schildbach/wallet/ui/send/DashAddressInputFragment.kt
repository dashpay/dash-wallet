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

package de.schildbach.wallet.ui.send

import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.findNavController
import com.google.common.base.Preconditions
import de.schildbach.wallet.Constants
import de.schildbach.wallet.ui.more.connections.protocol.DashConnectUri
import de.schildbach.wallet_test.R
import org.dash.wallet.common.payments.parsers.DashPaymentParsers
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.address_input.AddressInputFragment
import org.dash.wallet.common.util.Constants.DASH_CURRENCY
import org.dash.wallet.common.util.safeNavigate
class DashAddressInputFragment : AddressInputFragment() {

    companion object {
        private val paymentParsers = DashPaymentParsers(Constants.NETWORK_PARAMETERS)
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.paymentParsers = paymentParsers
        Preconditions.checkState(viewModel.currency == DASH_CURRENCY)
    }

    override fun handleSpecialInput(input: String): Boolean {
        if (DashConnectUri.isKeyUri(input) || DashConnectUri.isStUri(input)) {
            // DashConnect QR (dash-key:/dash-st:) — handled by the Connections screen
            safeNavigate(DashAddressInputFragmentDirections.addressInputToConnections(input))
            return true
        }
        return false
    }
    override fun continueAction() {
        SendCoinsActivity.start(requireActivity(), viewModel.addressResult.paymentIntent!!)
        viewModel.logEvent(AnalyticsConstants.AddressInput.CONTINUE)
        requireActivity().overridePendingTransition(R.anim.slide_in_right, R.anim.activity_stay)
        findNavController().popBackStack()
    }
}
