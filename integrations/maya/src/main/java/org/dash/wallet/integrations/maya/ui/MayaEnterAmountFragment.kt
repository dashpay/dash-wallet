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

import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import org.dash.wallet.common.data.PaymentIntent
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.integrations.maya.R
import org.dash.wallet.integrations.maya.databinding.FragmentMayaEnterAmountBinding

class MayaEnterAmountFragment : Fragment(R.layout.fragment_maya_enter_amount) {
    private val binding by viewBinding(FragmentMayaEnterAmountBinding::bind)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val paymentIntent: PaymentIntent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireArguments().getParcelable("paymentIntent", PaymentIntent::class.java)
        } else {
            requireArguments().getParcelable("paymentIntent")
        }
        binding.currency.text = requireArguments().getString("currency") + ": " + paymentIntent?.toString()
    }
}
