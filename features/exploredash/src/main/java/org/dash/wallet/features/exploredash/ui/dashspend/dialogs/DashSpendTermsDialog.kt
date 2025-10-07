/*
 * Copyright 2023 Dash Core Group.
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

package org.dash.wallet.features.exploredash.ui.dashspend.dialogs

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentActivity
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.ui.wiggle
import org.dash.wallet.common.util.openCustomTab
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.databinding.DialogDashspendTermsBinding
import org.dash.wallet.features.exploredash.ui.dashspend.DashSpendViewModel
import org.dash.wallet.features.exploredash.utils.exploreViewModels

class DashSpendTermsDialog : OffsetDialogFragment(R.layout.dialog_dashspend_terms) {

    companion object {
        private const val ARG_TERMS_LINK = "arg_terms_link"
        
        fun newInstance(termsLink: String): DashSpendTermsDialog {
            return DashSpendTermsDialog().apply {
                arguments = bundleOf(ARG_TERMS_LINK to termsLink)
            }
        }
    }
    override val forceExpand: Boolean = true
    private val viewModel by exploreViewModels<DashSpendViewModel>()
    private val binding by viewBinding(DialogDashspendTermsBinding::bind)
    private var onResultListener: (() -> Unit)? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.createAccountButton.setOnClickListener {
            onResultListener?.invoke()
            dismiss()
        }

        binding.termsLink.setOnClickListener {
            viewModel.openedCTXSpendTermsAndConditions = true
            val termsLink = arguments?.getString(ARG_TERMS_LINK) ?: ""
            requireActivity().openCustomTab(termsLink)
        }

        binding.acceptTermsCheckbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !viewModel.openedCTXSpendTermsAndConditions) {
                binding.acceptTermsCheckbox.isChecked = false
                binding.termsLink.wiggle()
            }

            binding.createAccountButton.isEnabled = binding.acceptTermsCheckbox.isChecked
        }
    }

    fun show(activity: FragmentActivity, onResult: () -> Unit) {
        onResultListener = onResult
        show(activity)
    }
}
