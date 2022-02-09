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

package org.dash.wallet.integrations.crowdnode.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.dash.wallet.common.services.NotificationService
import org.dash.wallet.common.services.SecurityModel
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.integrations.crowdnode.R
import org.dash.wallet.integrations.crowdnode.api.SignUpStatus
import org.dash.wallet.integrations.crowdnode.databinding.FragmentNewAccountBinding
import javax.inject.Inject

@AndroidEntryPoint
class NewAccountFragment : Fragment(R.layout.fragment_new_account) {
    private val binding by viewBinding(FragmentNewAccountBinding::bind)
    private val viewModel: CrowdNodeViewModel by activityViewModels()

    @Inject
    lateinit var securityModel: SecurityModel

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.dashAddressTxt.text = viewModel.dashAccountAddress
        val existingAccount = false // TODO: online account

        binding.title.setText(if (existingAccount) {
            R.string.account_exist_title
        } else {
            R.string.new_account
        })

        binding.createAccountBtn.setText(if (existingAccount) {
            R.string.account_link
        } else {
            R.string.account_create
        })

        binding.titleBar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        binding.createAccountBtn.setOnClickListener {
            GlobalScope.launch(Dispatchers.Main) {
                securityModel.requestPinCode(requireActivity())?.let {
                    // Launching in the global scope so that signup doesn't stop when staking is exited.
                    viewModel.signUp()
                }
            }
        }

        setTermsTextView(binding.acceptTermsTxt)

        binding.acceptTermsCheckbox.setOnCheckedChangeListener { _, isChecked ->
            viewModel.termsAccepted.value = isChecked
        }

        binding.notifyWhenDone.setOnClickListener {
            val intent = Intent(requireContext(), requireActivity()::class.java)
            viewModel.changeNotifyWhenDone(true, intent)
            requireActivity().finish()
        }

        binding.copyAddressBtn.setOnClickListener {
            (requireActivity().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).run {
                setPrimaryClip(ClipData.newPlainText("dash address", viewModel.dashAccountAddress))
            }
            Toast.makeText(requireContext(), getString(R.string.copied), Toast.LENGTH_SHORT).show()
        }

        viewModel.termsAccepted.observe(viewLifecycleOwner) {
            binding.createAccountBtn.isEnabled = it
        }

        viewModel.crowdNodeSignUpStatus.observe(viewLifecycleOwner) {
            binding.registerPanel.isVisible = it == SignUpStatus.NotStarted
            binding.inProgressPanel.isVisible = it != SignUpStatus.NotStarted

            when (it) {
                SignUpStatus.Finished -> {
                    Log.i("CROWDNODE", "Finished, safeNavigate")
                    safeNavigate(NewAccountFragmentDirections.newAccountToPortal())
                }
                SignUpStatus.AcceptingTerms -> binding.progressMessage.text = getString(R.string.accepting_terms)
                else -> binding.progressMessage.text = getString(R.string.crowdnode_creating)
            }
        }
    }

    private fun setTermsTextView(textView: TextView) {
        val termsOfService = getString(R.string.terms_of_use)
        val privacyPolicy = getString(R.string.privacy_policy)
        val completeString = getString(R.string.crowdnode_agree_to_terms, termsOfService, privacyPolicy)
        val spannableStringBuilder = SpannableStringBuilder(completeString)

        val clickOnTerms = object : ClickableSpan() {
            override fun onClick(widget: View) {
                safeNavigate(NewAccountFragmentDirections.newAccountToWebview(
                    getString(R.string.terms_of_use),
                    getString(R.string.terms_of_use_url)
                ))
            }
        }

        val clickOnPrivacy = object : ClickableSpan() {
            override fun onClick(widget: View) {
                safeNavigate(NewAccountFragmentDirections.newAccountToWebview(
                    getString(R.string.privacy_policy),
                    getString(R.string.privacy_policy_url)
                ))
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
}