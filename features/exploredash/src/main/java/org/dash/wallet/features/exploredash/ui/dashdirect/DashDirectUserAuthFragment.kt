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

package org.dash.wallet.features.exploredash.ui.dashdirect

import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.annotation.StringRes
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.enter_amount.NumericKeyboardView
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.KeyboardUtil
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.databinding.FragmentDashDirectUserAuthBinding
import org.dash.wallet.features.exploredash.utils.exploreViewModels

@AndroidEntryPoint
class DashDirectUserAuthFragment : Fragment(R.layout.fragment_dash_direct_user_auth) {
    private var currentDirectUserAuthType: DashDirectUserAuthType? = null
    private val binding by viewBinding(FragmentDashDirectUserAuthBinding::bind)
    private val viewModel by exploreViewModels<DashDirectViewModel>()

    enum class DashDirectUserAuthType(
        @StringRes val screenTitle: Int,
        @StringRes val screenSubtitle: Int,
        @StringRes val textInputHint: Int
    ) {
        CREATE_ACCOUNT(R.string.create_dash_direct_account, R.string.log_in_to_dashdirect_account_desc, R.string.email),
        SIGN_IN(R.string.log_in_to_dashdirect_account, R.string.log_in_to_dashdirect_account_desc, R.string.email),
        OTP(R.string.enter_verification_code, R.string.check_your_email_and_verification_code, R.string.password)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.titleBar.setNavigationOnClickListener {
            hideKeyboard()

            findNavController().popBackStack()
        }
        binding.continueButton.isEnabled = false

        currentDirectUserAuthType =
            (arguments?.getSerializable("dashDirectUserAuthType") as? DashDirectUserAuthType)?.also {
                binding.title.setText(it.screenTitle)
                binding.descLabel.setText(it.screenSubtitle)
                binding.inputWrapper.setHint(it.textInputHint)
            }

        binding.input.doOnTextChanged { text, _, _, _ ->
            binding.inputWrapper.isErrorEnabled = false
            binding.inputErrorTv.isVisible = false

            if (currentDirectUserAuthType != DashDirectUserAuthType.OTP) {
                binding.continueButton.isEnabled = isEmail(text)
            } else {
                binding.continueButton.isEnabled = !text.isNullOrEmpty()
            }
        }

        binding.input.setOnEditorActionListener { _, actionId, _ ->
            when (actionId) {
                EditorInfo.IME_ACTION_NEXT -> {
                    continueAction()
                    true
                }
                else -> false
            }
        }

        binding.continueButton.setOnClickListener {
            hideKeyboard()
            continueAction()
        }

        when (currentDirectUserAuthType) {
            DashDirectUserAuthType.SIGN_IN,
            DashDirectUserAuthType.CREATE_ACCOUNT -> {
                binding.continueButtonLayout.isVisible = true
                binding.bottomCard.isVisible = false
                binding.input.postDelayed({ showKeyboard() }, 100)
            }
            DashDirectUserAuthType.OTP -> {
                binding.continueButtonLayout.isVisible = false
                binding.bottomCard.isVisible = true
            }
            else -> {}
        }

        binding.verifyBtn.setOnClickListener {
            val input = binding.input.text.toString()
            verifyEmail(input)
        }

        binding.input.doOnTextChanged { text, _, _, _ ->
            if (currentDirectUserAuthType == DashDirectUserAuthType.OTP) {
                binding.verifyBtn.isEnabled = !text.isNullOrEmpty()
            }
        }
        binding.keyboardView.onKeyboardActionListener = keyboardActionListener
    }

    private val keyboardActionListener = object : NumericKeyboardView.OnKeyboardActionListener {
        var value = StringBuilder()

        fun refreshValue() {
            value.clear()
            value.append(binding.input.text.toString())
        }

        override fun onNumber(number: Int) {
            refreshValue()
            value.append(number)
            applyNewValue(value.toString())
        }

        override fun onBack(longClick: Boolean) {
            refreshValue()
            if (longClick) {
                value.clear()
            } else if (value.isNotEmpty()) {
                value.deleteCharAt(value.length - 1)
            }
            applyNewValue(value.toString())
        }

        override fun onFunction() {}
    }

    private fun applyNewValue(value: String) {
        binding.input.setText(value)
    }

    private fun continueAction() {
        showLoading()
        val input = binding.input.text.toString()
        when (currentDirectUserAuthType) {
            DashDirectUserAuthType.SIGN_IN -> authUserToDashDirect(input, true)
            DashDirectUserAuthType.CREATE_ACCOUNT -> authUserToDashDirect(input, false)
            DashDirectUserAuthType.OTP -> verifyEmail(input)
            else -> {}
        }
    }

    private fun showLoading() {
        binding.continueButton.text = ""
        binding.continueButtonLoading.isVisible = true
        binding.continueButton.isClickable = false
    }

    private fun hideLoading() {
        binding.continueButton.setText(R.string.continue_text)
        binding.continueButtonLoading.isGone = true
        binding.continueButton.isClickable = true
    }
    private fun authUserToDashDirect(email: String, isSignIn: Boolean) {
        lifecycleScope.launch {
            when (
                val response = if (isSignIn) {
                    viewModel.signInToDashDirect(email)
                } else {
                    viewModel.createUserToDashDirect(email)
                }
            ) {
                is ResponseResource.Success -> {
                    if (response.value) {
                        safeNavigate(
                            DashDirectUserAuthFragmentDirections.authToDashDirectUserAuthFragment(
                                DashDirectUserAuthType.OTP
                            )
                        )
                    }
                }
                is ResponseResource.Failure -> {
                    viewModel.logEvent(AnalyticsConstants.DashDirect.UNSUCCESSFUL_LOGIN)
                    binding.inputWrapper.isErrorEnabled = true
                    binding.inputErrorTv.text =
                        if (response.errorBody.isNullOrEmpty()) getString(R.string.error) else response.errorBody
                    binding.inputErrorTv.isVisible = true
                }
            }
            hideLoading()
        }
    }

    private fun verifyEmail(code: String) {
        lifecycleScope.launch {
            when (val response = viewModel.verifyEmail(code)) {
                is ResponseResource.Success -> {
                    if (response.value) {
                        viewModel.logEvent(AnalyticsConstants.DashDirect.SUCCESSFUL_LOGIN)
                        hideKeyboard()
                        safeNavigate(DashDirectUserAuthFragmentDirections.authToPurchaseGiftCardFragment())
                    }
                }
                is ResponseResource.Failure -> {
                    binding.inputWrapper.isErrorEnabled = true
                    binding.inputErrorTv.text = getString(R.string.invaild_code)
                    binding.inputErrorTv.isVisible = true
                }
            }
            hideLoading()
        }
    }

    private fun isEmail(text: CharSequence?): Boolean {
        return !text.isNullOrEmpty() && Patterns.EMAIL_ADDRESS.matcher(text).matches()
    }

    private fun showKeyboard() {
        binding.input.requestFocus()
        KeyboardUtil.showSoftKeyboard(requireContext(), binding.input)
    }

    private fun hideKeyboard() {
        KeyboardUtil.hideKeyboard(requireContext(), binding.input)
    }
}
