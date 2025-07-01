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

package org.dash.wallet.features.exploredash.ui.dashspend

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
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.transactions.ByAddressCoinSelector
import org.dash.wallet.common.ui.enter_amount.NumericKeyboardView
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.KeyboardUtil
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProvider
import org.dash.wallet.features.exploredash.databinding.FragmentDashSpendUserAuthBinding
import org.dash.wallet.features.exploredash.utils.exploreViewModels
import org.slf4j.LoggerFactory
import retrofit2.HttpException

@AndroidEntryPoint
class DashSpendUserAuthFragment : Fragment(R.layout.fragment_dash_spend_user_auth) {
    companion object {
        private val log = LoggerFactory.getLogger(DashSpendUserAuthFragment::class.java)
    }

    private val binding by viewBinding(FragmentDashSpendUserAuthBinding::bind)
    private val viewModel by exploreViewModels<DashSpendViewModel>()
    private val args by navArgs<DashSpendUserAuthFragmentArgs>()

    enum class AuthType(
        @StringRes val screenTitle: Int,
        @StringRes val textInputHint: Int
    ) {
        CREATE_ACCOUNT(R.string.create_ctx_spend_account, R.string.email),
        SIGN_IN(R.string.log_in_to_ctxspend_account, R.string.email),
        OTP(R.string.enter_verification_code, R.string.password)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.titleBar.setNavigationOnClickListener {
            hideKeyboard()
            findNavController().popBackStack()
        }
        binding.continueButton.isEnabled = false

        val authType = args.authType
        binding.title.setText(authType.screenTitle)
        binding.inputWrapper.setHint(authType.textInputHint)
        binding.descLabel.setText(when(authType) {
            AuthType.OTP -> R.string.verification_check_email
            else -> args.service.disclaimer
        })

        binding.input.doOnTextChanged { text, _, _, _ ->
            binding.inputWrapper.isErrorEnabled = false
            binding.inputErrorTv.isVisible = false

            if (authType != AuthType.OTP) {
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
            AuthType.SIGN_IN,
            AuthType.CREATE_ACCOUNT -> {
                binding.bottomCard.isVisible = false
                binding.input.postDelayed({ showKeyboard() }, 100)
                binding.input.showSoftInputOnFocus = true

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    binding.input.setAutofillHints(View.AUTOFILL_HINT_EMAIL_ADDRESS)
                }
            }
            AuthType.OTP -> {
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
        when (args.authType) {
            AuthType.SIGN_IN -> authUser(args.service, input, true)
            AuthType.CREATE_ACCOUNT -> authUser(args.service, input, false)
            AuthType.OTP -> verifyEmail(args.service, input)
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

    private fun authUser(provider: GiftCardProvider, email: String, isSignIn: Boolean) {
        lifecycleScope.launch {
            var errorMessage: String

            try {
                val success = if (isSignIn) {
                    viewModel.signIn(provider, email)
                } else {
                    viewModel.signUp(provider, email)
                }

                if (success) {
                    safeNavigate(
                        DashSpendUserAuthFragmentDirections.authToCtxSpendUserAuthFragment(
                            AuthType.OTP,
                            provider
                        )
                    )

                    hideLoading()
                    return@launch
                } else {
                    errorMessage = getString(R.string.login_error_title, provider.name)
                }
            } catch (e: Exception) {
                log.error("DashSpend: error during signup/login to ${provider.name}: ${e::class.simpleName} - ${e.message}", e)

                val message = when (e) {
                    is HttpException -> e.response()?.errorBody()?.string()
                    else -> e.message
                }
                errorMessage = message ?: getString(R.string.error)
            }

            hideLoading()
            viewModel.logEvent(AnalyticsConstants.DashSpend.UNSUCCESSFUL_LOGIN)
            binding.inputWrapper.isErrorEnabled = true
            binding.inputErrorTv.isVisible = true
            binding.inputErrorTv.text = errorMessage
        }
    }

    private fun verifyEmail(provider: GiftCardProvider, code: String) {
        lifecycleScope.launch {
            try {
                val success = viewModel.verifyEmail(provider, code)
                if (success) {
                    viewModel.logEvent(AnalyticsConstants.DashSpend.SUCCESSFUL_LOGIN)
                    hideKeyboard()
                    safeNavigate(DashSpendUserAuthFragmentDirections.authToPurchaseGiftCardFragment())
                }
            } catch (e: Exception) {
                binding.inputWrapper.isErrorEnabled = true
                binding.inputErrorTv.text = getString(R.string.invaild_code)
                binding.inputErrorTv.isVisible = true
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
