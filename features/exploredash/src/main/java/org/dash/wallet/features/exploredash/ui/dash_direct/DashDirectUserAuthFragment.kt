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

package org.dash.wallet.features.exploredash.ui.dash_direct

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.databinding.FragmentDashDirectUserAuthBinding
import org.dash.wallet.features.exploredash.utils.exploreViewModels
import org.slf4j.LoggerFactory

@FlowPreview
@ExperimentalCoroutinesApi
@AndroidEntryPoint
class DashDirectUserAuthFragment : Fragment(R.layout.fragment_dash_direct_user_auth) {
    companion object {
        private val log = LoggerFactory.getLogger(DashDirectUserAuthFragment::class.java)
    }

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

        binding.input.postDelayed({ showKeyboard() }, 100)

        currentDirectUserAuthType =
            (arguments?.getSerializable("dashDirectUserAuthType") as? DashDirectUserAuthType)?.also {
                binding.title.setText(it.screenTitle)
                binding.descLabel.setText(it.screenSubtitle)
                binding.inputWrapper.setHint(it.textInputHint)
            }

        binding.input.doOnTextChanged { text, _, _, _ ->
            binding.inputWrapper.isErrorEnabled = false
            binding.inputErrorTv.isVisible = false

            if (currentDirectUserAuthType != DashDirectUserAuthType.OTP)
                binding.continueButton.isEnabled = isEmail(text)
            else binding.continueButton.isEnabled = !text.isNullOrEmpty()
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
    }

    private fun continueAction() {
        val input = binding.input.text.toString()
        when (currentDirectUserAuthType) {
            DashDirectUserAuthType.SIGN_IN -> authUserToDashDirect(input, true)
            DashDirectUserAuthType.CREATE_ACCOUNT -> authUserToDashDirect(input, false)
            DashDirectUserAuthType.OTP -> verifyEmail(input)
            else -> {}
        }
    }

    private fun authUserToDashDirect(email: String, isSignIn: Boolean) {
        lifecycleScope.launch {
            when (
                val response =
                    if (isSignIn) viewModel.signInToDashDirect(email)
                    else viewModel.createUserToDashDirect(email)
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
                    binding.inputWrapper.isErrorEnabled = true
                    binding.inputErrorTv.text =
                        if (response.errorBody.isNullOrEmpty()) getString(R.string.error) else response.errorBody
                    binding.inputErrorTv.isVisible = true
                }
            }
        }
    }

    private fun verifyEmail(code: String) {
        lifecycleScope.launch {
            when (val response = viewModel.verifyEmail(code)) {
                is ResponseResource.Success -> {
                    if (response.value) {
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
        }
    }

    private fun isEmail(text: CharSequence?): Boolean {
        return !text.isNullOrEmpty() && Patterns.EMAIL_ADDRESS.matcher(text).matches()
    }

    private fun showKeyboard() {
        binding.input.requestFocus()
        val inputManager = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        inputManager?.showSoftInput(binding.input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val inputManager = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        inputManager?.hideSoftInputFromWindow(binding.input.windowToken, 0)
        binding.root.setPadding(0, 0, 0, 0)
    }
}
