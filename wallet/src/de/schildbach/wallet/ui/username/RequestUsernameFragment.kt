package de.schildbach.wallet.ui.username

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputLayout
import de.schildbach.wallet.Constants
import de.schildbach.wallet.ui.dashpay.DashPayViewModel
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentRequestUsernameBinding
import kotlinx.coroutines.delay
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.KeyboardUtil

class RequestUsernameFragment : Fragment(R.layout.fragment_request_username) {
    private val binding by viewBinding(FragmentRequestUsernameBinding::bind)

    private val dashPayViewModel: DashPayViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.titleBar.setNavigationOnClickListener {
            requireActivity().finish()
        }

        binding.usernameInput.doOnTextChanged { text, _, _, _ ->
            val username = text.toString()
            binding.requestUsernameButton.isEnabled = username.isNotEmpty()
            binding.usernameRequested.isVisible = username.isNotEmpty()
            binding.inputWrapper.isEndIconVisible = username.isNotEmpty()
        }

        binding.inputWrapper.endIconMode = TextInputLayout.END_ICON_CUSTOM
        binding.inputWrapper.setEndIconOnClickListener {
            binding.usernameInput.text?.clear()
        }

        binding.requestUsernameButton.setOnClickListener {
            // TODO: set navigation to voting fragment
        }

        lifecycleScope.launchWhenCreated {
            delay(250) // Wait for the dialog animation to finish before raising keyboard
            showKeyboard()
        }

        showKeyboard()

        binding.balanceRequirementDisclaimer.text = getString(
            R.string.dashpay_min_balance_disclaimer,
            MonetaryFormat.BTC.format(Constants.DASH_PAY_FEE)
        )
    }

    private fun showKeyboard() {
        binding.usernameInput.requestFocus()
        KeyboardUtil.showSoftKeyboard(requireContext(), binding.usernameInput)
    }

    private fun hideKeyboard() {
        KeyboardUtil.hideKeyboard(requireContext(), binding.usernameInput)
    }
}
