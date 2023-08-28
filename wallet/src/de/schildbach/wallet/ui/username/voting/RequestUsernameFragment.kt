package de.schildbach.wallet.ui.username.voting

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet.ui.dashpay.DashPayViewModel
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentRequestUsernameBinding
import kotlinx.coroutines.delay
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.KeyboardUtil
import org.dash.wallet.common.util.observe
import org.dash.wallet.common.util.safeNavigate


@AndroidEntryPoint
class RequestUsernameFragment : Fragment(R.layout.fragment_request_username) {
    private val binding by viewBinding(FragmentRequestUsernameBinding::bind)

    private val dashPayViewModel: DashPayViewModel by activityViewModels()
    private val requestUserNameViewModel by activityViewModels<RequestUserNameViewModel>()
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.titleBar.setNavigationOnClickListener {
            requireActivity().finish()
        }

        binding.usernameInput.doOnTextChanged { text, _, _, _ ->
            val username = text.toString()
            binding.requestUsernameButton.isEnabled = username.isNotEmpty()
            binding.inputWrapper.isEndIconVisible = username.isNotEmpty()
            // TODO: Replace with api to verify username
            val isUsernameValid = binding.usernameInput.text.contentEquals("test") ||
                binding.usernameInput.text.contentEquals("admin")
            binding.usernameRequested.isVisible = isUsernameValid
        }

        binding.inputWrapper.endIconMode = TextInputLayout.END_ICON_CUSTOM
        binding.inputWrapper.setEndIconOnClickListener {
            binding.usernameInput.text?.clear()
        }

        binding.requestUsernameButton.setOnClickListener {
            AdaptiveDialog.create(
                R.drawable.ic_verify_identity,
                getString(R.string.verify_your_identity),
                getString(
                    R.string.if_somebody
                ),
                getString(
                    R.string.skip
                ),
                getString(
                    R.string.verify
                )
            ).show(requireActivity()) {
                requestUserNameViewModel.requestedUserName = binding.usernameInput.text.toString()
                if (it == true) {
                    safeNavigate(
                        RequestUsernameFragmentDirections.requestUsernameFragmentToVerifyIdentityFragment(
                            binding.usernameInput.text.toString()
                        )
                    )
                } else {
                    safeNavigate(
                        RequestUsernameFragmentDirections.requestsToConfirmUsernameRequestDialog()
                    )
                }
            }
        }

        lifecycleScope.launchWhenCreated {
            delay(250) // Wait for the dialog animation to finish before raising keyboard
            showKeyboard()
        }


        binding.balanceRequirementDisclaimer.text = getString(
            R.string.dashpay_min_balance_disclaimer,
            MonetaryFormat.BTC.format(Constants.DASH_PAY_FEE)
        )

        requestUserNameViewModel.uiState.observe(viewLifecycleOwner) {
            if (it.usernameSubmittedSuccess) {
                requireActivity().finish()
            }

            if (it.usernameSubmittedError) {
                showErrorDialog()
            }

            if (it.usernameVerified) {
                binding.usernameInput.isFocusable = (false)
                hideKeyboard()
                safeNavigate(
                    RequestUsernameFragmentDirections.requestsToConfirmUsernameRequestDialog()
                )
            }
        }
    }

    private fun showKeyboard() {
        binding.usernameInput.requestFocus()
        KeyboardUtil.showSoftKeyboard(requireContext(), binding.usernameInput)
    }

    private fun hideKeyboard() {
        KeyboardUtil.hideKeyboard(requireContext(), binding.usernameInput)
    }

    private fun showErrorDialog() {
        val dialog = AdaptiveDialog.create(
            R.drawable.ic_error,
            getString(R.string.something_wrong_title),
            getString(R.string.there_was_a_network_error),
            getString(R.string.close),
            getString(R.string.try_again)

        )
        dialog.show(requireActivity()) {
            if (it == true) {
                requestUserNameViewModel.submit()
            }
        }
    }
}
