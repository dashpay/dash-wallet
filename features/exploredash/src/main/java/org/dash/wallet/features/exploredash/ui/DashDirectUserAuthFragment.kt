/*
 * Copyright 2021 Gabor Varadi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dash.wallet.features.exploredash.ui

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.navGraphViewModels
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.databinding.FragmentDashDirectUserAuthBinding
import org.slf4j.LoggerFactory

@FlowPreview
@ExperimentalCoroutinesApi
@AndroidEntryPoint
class DashDirectUserAuthFragment : Fragment(R.layout.fragment_dash_direct_user_auth) {
    companion object {
        private val log = LoggerFactory.getLogger(DashDirectUserAuthFragment::class.java)
    }

    private var currentDirectUserAuthType: DashDirectUserAuthType?=null
    private val binding by viewBinding(FragmentDashDirectUserAuthBinding::bind)
    private val exploreViewModel: ExploreViewModel by navGraphViewModels(R.id.explore_dash) { defaultViewModelProviderFactory }

    enum class DashDirectUserAuthType(
        @StringRes val screenTitle: Int,
        @StringRes val screenSubtitle: Int,
        @StringRes val textInputHint: Int
    ) {
        CREATE_ACCOUNT(
            R.string.create_dash_direct_account,
            R.string.log_in_to_dashdirect_account_desc,
            R.string.email
        ),
        SIGN_IN(
            R.string.log_in_to_dashdirect_account,
            R.string.log_in_to_dashdirect_account_desc,
            R.string.email
        ),
        OTP(
            R.string.enter_verification_code,
            R.string.check_your_email_and_verification_code,
            R.string.password
        );
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.titleBar.setNavigationOnClickListener {
            hideKeyboard()

            findNavController().popBackStack()
        }
        binding.continueButton.isEnabled = false

        binding.input.postDelayed({
               showKeyboard()
            },
            100
        )

        currentDirectUserAuthType=   (arguments?.getSerializable("dashDirectUserAuthType") as? DashDirectUserAuthType)?.also {
             binding.title.setText(it.screenTitle)
             binding.descLabel.setText(it.screenSubtitle)
             binding.inputWrapper.setHint(it.textInputHint)
        }

        binding.input.doOnTextChanged { text, _, _, _ ->

            binding.inputWrapper.isErrorEnabled = false
            binding.inputErrorTv.isVisible = false

            if(currentDirectUserAuthType!=DashDirectUserAuthType.OTP )
                binding.continueButton.isEnabled =isEmail(text)
            else
                binding.continueButton.isEnabled = !text.isNullOrEmpty()
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

    val continueAction = {
        val input = binding.input.text.toString()
        when (currentDirectUserAuthType) {
            DashDirectUserAuthType.SIGN_IN -> authUserToDashDirect(input, true)
            DashDirectUserAuthType.CREATE_ACCOUNT -> authUserToDashDirect(input, false)
            DashDirectUserAuthType.OTP -> verifyEmail(input)
        }
    }

    private fun authUserToDashDirect(
        email: String,
        isSignIn:Boolean
    ) {
        lifecycleScope.launch {
            when (val response = if(isSignIn )
                exploreViewModel.signInToDashDirect(email)
            else
                exploreViewModel.createUserToDashDirect(email)) {
                is ResponseResource.Success -> {
                    if (response.value) {
                        safeNavigate(DashDirectUserAuthFragmentDirections.
                                authToDashDirectUserAuthFragment(
                                DashDirectUserAuthType.OTP))
                    }
                }

                is ResponseResource.Failure -> {
                    binding.inputWrapper.isErrorEnabled = true
                    binding.inputErrorTv.text = getString(R.string.error)
                    binding.inputErrorTv.isVisible = true
                }
            }
        }
    }

    private fun verifyEmail(code: String) {
        lifecycleScope.launch {
                when (val response = exploreViewModel.verifyEmail(code)) {
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
        return !text.isNullOrEmpty() &&
                Patterns.EMAIL_ADDRESS.matcher(text).matches()
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
