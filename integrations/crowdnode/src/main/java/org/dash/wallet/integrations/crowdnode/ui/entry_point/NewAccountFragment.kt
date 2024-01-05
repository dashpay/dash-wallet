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
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.dash.wallet.common.services.AuthenticationManager
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.copy
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.integrations.crowdnode.R
import org.dash.wallet.integrations.crowdnode.databinding.FragmentNewAccountBinding
import org.dash.wallet.integrations.crowdnode.model.OnlineAccountStatus
import org.dash.wallet.integrations.crowdnode.model.SignUpStatus
import org.dash.wallet.integrations.crowdnode.ui.CrowdNodeViewModel
import javax.inject.Inject

@AndroidEntryPoint
class NewAccountFragment : Fragment(R.layout.fragment_new_account) {
    private val binding by viewBinding(FragmentNewAccountBinding::bind)
    private val viewModel: CrowdNodeViewModel by activityViewModels()
    private val args by navArgs<NewAccountFragmentArgs>()

    @Inject
    lateinit var securityFunctions: AuthenticationManager

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (args.existingAccount) {
            binding.title.setText(R.string.crowdnode_link)
            binding.createAccountBtn.setText(R.string.crowdnode_login)
            binding.description1.setText(R.string.crowdnode_link_account_description)
            binding.description2.isVisible = false
            binding.title.gravity = Gravity.CENTER
            binding.description1.gravity = Gravity.CENTER
        }

        binding.titleBar.setNavigationOnClickListener {
            if (findNavController().previousBackStackEntry != null) {
                findNavController().popBackStack()
            } else {
                requireActivity().finish()
            }
        }

        binding.createAccountBtn.setOnClickListener {
            if (args.existingAccount) {
                continueLinking()
            } else {
                continueSignUp()
            }
        }

        setTermsTextView(binding.acceptTermsTxt)

        binding.acceptTermsCheckbox.setOnCheckedChangeListener { _, isChecked ->
            viewModel.termsAccepted.value = isChecked
        }

        binding.notifyWhenDone.setOnClickListener {
            viewModel.logEvent(AnalyticsConstants.CrowdNode.NOTIFY_WHEN_CREATED)
            requireActivity().finish()
        }

        binding.copyAddressBtn.setOnClickListener {
            viewModel.accountAddress.value?.toBase58()?.copy(requireActivity(), "dash address")
        }

        viewModel.termsAccepted.observe(viewLifecycleOwner) {
            binding.createAccountBtn.isEnabled = it
        }

        viewModel.accountAddress.observe(viewLifecycleOwner) {
            binding.dashAddressTxt.text = it.toBase58()
        }

        viewModel.observeSignUpStatus().observe(viewLifecycleOwner) {
            binding.registerPanel.isVisible = it == SignUpStatus.NotStarted || it == SignUpStatus.Error
            binding.inProgressPanel.isVisible = it != SignUpStatus.NotStarted && it != SignUpStatus.Error

            when (it) {
                SignUpStatus.Finished -> safeNavigate(NewAccountFragmentDirections.newAccountToPortal())
                SignUpStatus.AcceptingTerms -> binding.progressMessage.text = getString(R.string.accepting_terms)
                SignUpStatus.Error -> showError()
                else -> binding.progressMessage.text = getString(R.string.crowdnode_creating)
            }
        }

        viewModel.observeOnlineAccountStatus().observe(viewLifecycleOwner) { onlineStatus ->
            if (onlineStatus != null && onlineStatus.ordinal >= OnlineAccountStatus.Validating.ordinal) {
                safeNavigate(NewAccountFragmentDirections.newAccountToPortal())
            }
        }

        viewModel.onlineAccountRequest.observe(viewLifecycleOwner) { args ->
            safeNavigate(
                NewAccountFragmentDirections.newAccountToWebView(
                    getString(R.string.crowdnode_login),
                    args[CrowdNodeViewModel.URL_ARG]!!,
                    true
                )
            )
        }
    }

    override fun onResume() {
        super.onResume()

        if (viewModel.onlineAccountStatus == OnlineAccountStatus.Linking) {
            viewModel.cancelLinkingOnlineAccount()
        }
    }

    private fun continueSignUp() {
        viewModel.logEvent(AnalyticsConstants.CrowdNode.CREATE_ACCOUNT_BUTTON)

        lifecycleScope.launch {
            securityFunctions.authenticate(requireActivity())?.let {
                viewModel.signUp()
            }
        }
    }

    private fun continueLinking() {
        viewModel.logEvent(AnalyticsConstants.CrowdNode.LINK_EXISTING_LOGIN_BUTTON)
        viewModel.linkOnlineAccount()
    }

    private fun setTermsTextView(textView: TextView) {
        val termsOfService = getString(R.string.terms_of_use)
        val privacyPolicy = getString(R.string.privacy_policy)
        val completeString = getString(R.string.crowdnode_agree_to_terms, termsOfService, privacyPolicy)
        val spannableStringBuilder = SpannableStringBuilder(completeString)

        val clickOnTerms = object : ClickableSpan() {
            override fun onClick(widget: View) {
                safeNavigate(
                    NewAccountFragmentDirections.newAccountToWebView(
                        getString(R.string.terms_of_use),
                        getString(R.string.crowdnode_terms_of_use_url)
                    )
                )
            }
        }

        val clickOnPrivacy = object : ClickableSpan() {
            override fun onClick(widget: View) {
                safeNavigate(
                    NewAccountFragmentDirections.newAccountToWebView(
                        getString(R.string.privacy_policy),
                        getString(R.string.crowdnode_privacy_policy_url)
                    )
                )
            }
        }

        var startIndex = completeString.indexOf(termsOfService)
        var endIndex = startIndex + termsOfService.length
        spannableStringBuilder.setSpan(clickOnTerms, startIndex, endIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        startIndex = completeString.indexOf(privacyPolicy)
        endIndex = startIndex + privacyPolicy.length
        spannableStringBuilder.setSpan(clickOnPrivacy, startIndex, endIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        textView.text = spannableStringBuilder
        textView.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun showError() {
        val title = getString(R.string.crowdnode_signup_error)
        safeNavigate(NewAccountFragmentDirections.newAccountToResult(true, title, ""))
    }
}
