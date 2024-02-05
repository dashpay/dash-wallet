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

package org.dash.wallet.features.exploredash.ui.ctxspend

import android.os.Build
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
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.enter_amount.NumericKeyboardView
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.KeyboardUtil
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.databinding.FragmentCtxSpendUserAuthBinding
import org.dash.wallet.features.exploredash.utils.exploreViewModels

@AndroidEntryPoint
class CTXSpendUserAuthFragment : Fragment(R.layout.fragment_ctx_spend_user_auth) {
    private val binding by viewBinding(FragmentCtxSpendUserAuthBinding::bind)
    private val viewModel by exploreViewModels<CTXSpendViewModel>()
    private val args by navArgs<CTXSpendUserAuthFragmentArgs>()

    enum class CTXSpendUserAuthType(
        @StringRes val screenTitle: Int,
        @StringRes val screenSubtitle: Int,
        @StringRes val textInputHint: Int
    ) {
        CREATE_ACCOUNT(R.string.create_ctx_spend_account, R.string.log_in_to_ctxspend_account_desc, R.string.email),
        SIGN_IN(R.string.log_in_to_ctxspend_account, R.string.log_in_to_ctxspend_account_desc, R.string.email),
        OTP(R.string.enter_verification_code, R.string.verification_check_email, R.string.password)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.titleBar.setNavigationOnClickListener {
            hideKeyboard()
            findNavController().popBackStack()
        }
        binding.continueButton.isEnabled = false

        val authType = args.ctxSpendUserAuthType
        binding.title.setText(authType.screenTitle)
        binding.descLabel.setText(authType.screenSubtitle)
        binding.inputWrapper.setHint(authType.textInputHint)

        binding.input.doOnTextChanged { text, _, _, _ ->
            binding.inputWrapper.isErrorEnabled = false
            binding.inputErrorTv.isVisible = false

            if (authType != CTXSpendUserAuthType.OTP) {
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

        when (authType) {
            CTXSpendUserAuthType.SIGN_IN,
            CTXSpendUserAuthType.CREATE_ACCOUNT -> {
                binding.bottomCard.isVisible = false
                binding.input.postDelayed({ showKeyboard() }, 100)
                binding.input.showSoftInputOnFocus = true

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    binding.input.setAutofillHints(View.AUTOFILL_HINT_EMAIL_ADDRESS)
                }
            }
            CTXSpendUserAuthType.OTP -> {
                binding.bottomCard.isVisible = true
                binding.input.showSoftInputOnFocus = false
                binding.input.requestFocus()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    binding.input.setAutofillHints("")
                }
            }
            else -> {}
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
        binding.input.setSelection(value.length)
    }

    private fun continueAction() {
        showLoading()
        val input = binding.input.text.toString()
        when (args.ctxSpendUserAuthType) {
            CTXSpendUserAuthType.SIGN_IN -> authUserToCTXSpend(input, true)
            CTXSpendUserAuthType.CREATE_ACCOUNT -> authUserToCTXSpend(input, false)
            CTXSpendUserAuthType.OTP -> verifyEmail(input)
            else -> {}
        }
    }

    private fun showLoading() {
        binding.continueButton.text = ""
        binding.continueButtonLoading.isVisible = true
        binding.continueButton.isClickable = false
    }

    private fun hideLoading() {
        binding.continueButton.setText(R.string.button_continue)
        binding.continueButtonLoading.isGone = true
        binding.continueButton.isClickable = true
    }

    private fun authUserToCTXSpend(email: String, isSignIn: Boolean) {
        lifecycleScope.launch {
            when (
                val response = viewModel.signInToCTXSpend(email)) {
                is ResponseResource.Success -> {
                    if (response.value) {
                        safeNavigate(
                            CTXSpendUserAuthFragmentDirections.authToCtxSpendUserAuthFragment(
                                CTXSpendUserAuthType.OTP
                            )
                        )
                    }
                }
                is ResponseResource.Failure -> {
                    viewModel.logEvent(AnalyticsConstants.DashSpend.UNSUCCESSFUL_LOGIN)
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
                        viewModel.logEvent(AnalyticsConstants.DashSpend.SUCCESSFUL_LOGIN)
                        hideKeyboard()
                        safeNavigate(CTXSpendUserAuthFragmentDirections.authToPurchaseGiftCardFragment())
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
