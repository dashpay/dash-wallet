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

package org.dash.wallet.integrations.maya.ui.dialogs

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.navigation.fragment.findNavController
import org.dash.wallet.common.UserInteractionAwareCallback
import org.dash.wallet.common.customtabs.CustomTabActivityHelper
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.integrations.maya.R
import org.dash.wallet.integrations.maya.databinding.DialogCoinbaseFeeInfoBinding

class MayaFeeInfoDialog : DialogFragment() {
    private val binding by viewBinding(DialogCoinbaseFeeInfoBinding::bind)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            requestFeature(Window.FEATURE_NO_TITLE)
        }
        return inflater.inflate(R.layout.dialog_coinbase_fee_info, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.coinbaseFeeInfoCloseBtn.setOnClickListener { findNavController().navigateUp() }
        binding.coinbaseFeeInfoLearnMore.setOnClickListener {
            findNavController().navigateUp()
            openWebPage()
        }
    }

    private fun openWebPage() {
        val feeInfoHelpLink = "https://help.coinbase.com/en/coinbase/trading-and-funding/pricing-and-fees/fees"
        val builder = CustomTabsIntent.Builder()
        val toolbarColor = ContextCompat.getColor(requireContext(), R.color.colorPrimary)
        val colorSchemeParams = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(toolbarColor)
            .build()
        val customTabsIntent = builder.setShowTitle(true)
            .setDefaultColorSchemeParams(colorSchemeParams)
            .build()

        val uri = Uri.parse(feeInfoHelpLink)
        CustomTabActivityHelper.openCustomTab(
            requireActivity(),
            customTabsIntent,
            uri
        ) { _, _ ->
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = uri
            startActivity(intent)
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.apply {
            window?.apply {
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                callback = UserInteractionAwareCallback(this.callback, requireActivity())
            }
        }
    }
}
