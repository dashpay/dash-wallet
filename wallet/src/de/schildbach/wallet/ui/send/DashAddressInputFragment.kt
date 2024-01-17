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
import com.google.common.base.Preconditions
import de.schildbach.wallet_test.R
import org.dash.wallet.common.payments.parsers.AddressParser.Companion.getDashAddressParser
import org.dash.wallet.common.payments.parsers.DashPaymentIntentParser
import org.dash.wallet.common.payments.parsers.Parsers
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.address_input.AddressInputFragment
import org.dash.wallet.common.util.Constants

class DashAddressInputFragment : AddressInputFragment() {

    init {
        if (Parsers.getAddressParser(Constants.DASH_CURRENCY) == null) {
            Parsers.add(
                "dash",
                "DASH",
                DashPaymentIntentParser(de.schildbach.wallet.Constants.NETWORK_PARAMETERS),
                getDashAddressParser(de.schildbach.wallet.Constants.NETWORK_PARAMETERS)
            )
        }
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Preconditions.checkState(viewModel.currency == Constants.DASH_CURRENCY)
    }
    override fun continueAction() {
        SendCoinsActivity.start(requireActivity(), viewModel.addressResult.value?.paymentIntent!!)
        viewModel.logEvent(AnalyticsConstants.AddressInput.CONTINUE)
        requireActivity().overridePendingTransition(R.anim.slide_in_right, R.anim.activity_stay)
    }
}
