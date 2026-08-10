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

package org.dash.wallet.integrations.crowdnode.ui.entry_point

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import dagger.hilt.android.AndroidEntryPoint
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.integrations.crowdnode.R
import org.dash.wallet.integrations.crowdnode.databinding.FragmentEntryPointBinding
import org.dash.wallet.integrations.crowdnode.ui.CrowdNodeViewModel
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConstants

@AndroidEntryPoint
class EntryPointFragment : Fragment(R.layout.fragment_entry_point) {
    private val binding by viewBinding(FragmentEntryPointBinding::bind)
    private val viewModel: CrowdNodeViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.titleBar.setNavigationOnClickListener {
            requireActivity().finish()
        }

        binding.requiredDashTxt.text = getString(
            R.string.required_dash_amount,
            CrowdNodeConstants.MINIMUM_REQUIRED_DASH.toPlainString()
        )

        binding.newAccountBtn.setOnClickListener {
            viewModel.logEvent(AnalyticsConstants.CrowdNode.CREATE_NEW_ACCOUNT)
            safeNavigate(EntryPointFragmentDirections.entryPointToNewAccount(false))
        }

        // CrowdNode functionality is limited: linking an existing account isn't supported
        binding.existingAccountBtn.isVisible = false

        // Account creation is fenced off (CrowdNodeConstants.
        // SIGNUP_AND_DEPOSITS_ENABLED). This is THE entry point to the signup
        // flow, so hiding the button here is what stops a user reaching the
        // fenced-off on-chain senders; CrowdNodeBlockchainApi's throws are
        // the backstop. Say why, rather than leaving an empty screen.
        //
        // The explanation replaces the screen's own title/hint, which sit
        // OUTSIDE the button card — requiredDashTxt is a child of
        // newAccountBtn and would be hidden along with it.
        if (!CrowdNodeConstants.SIGNUP_AND_DEPOSITS_ENABLED) {
            binding.newAccountBtn.isVisible = false
            binding.getStartedTitle.text = getString(R.string.crowdnode_signup_deposits_disabled)
            binding.getStartedHint.text = getString(R.string.crowdnode_signup_deposits_disabled_message)
        }

        binding.backupPassphraseHint.setOnClickListener {
            val dialog = AdaptiveDialog.create(
                null,
                getString(R.string.crowdnode_secure_wallet),
                getString(R.string.crowdnode_secure_wallet_explainer),
                getString(R.string.button_close),
                getString(R.string.backup_passphrase)
            )

            dialog.show(requireActivity()) { result ->
                if (result == true) {
                    viewModel.backupPassphrase()
                }
            }
        }

        binding.requiredDashHint.setOnClickListener {
            val dialog = AdaptiveDialog.create(
                null,
                getString(R.string.insufficient_funds),
                getString(
                    R.string.crowdnode_minimum_dash,
                    CrowdNodeConstants.MINIMUM_REQUIRED_DASH.toPlainString()
                ),
                getString(R.string.button_close),
                getString(R.string.buy_dash)
            )

            dialog.show(requireActivity()) { result ->
                if (result == true) {
                    viewModel.buyDash()
                }
            }
        }

        viewModel.hasEnoughBalance.observe(viewLifecycleOwner) {
            displayNewAccountRequirements(viewModel.needPassphraseBackUp, it)
        }
    }

    private fun displayNewAccountRequirements(needBackup: Boolean, enoughBalance: Boolean) {
        binding.errorPassphrase.isVisible = needBackup
        binding.errorBalance.isVisible = !enoughBalance

        val disableNewAccount = needBackup || !enoughBalance
        binding.newAccountBtn.isClickable = !disableNewAccount
        binding.newAccountBtn.isFocusable = !disableNewAccount

        if (disableNewAccount) {
            binding.newAccountBtnTitle.alpha = 0.2f
        } else {
            binding.newAccountBtnTitle.alpha = 1f
        }
    }

    override fun onResume() {
        super.onResume()
        displayNewAccountRequirements(
            viewModel.needPassphraseBackUp,
            viewModel.hasEnoughBalance.value ?: false
        )
    }
}
