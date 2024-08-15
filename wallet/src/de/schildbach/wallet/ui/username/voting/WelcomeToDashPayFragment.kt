package de.schildbach.wallet.ui.username.voting

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentWelcomeToDashpayBinding
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.safeNavigate

@AndroidEntryPoint
class WelcomeToDashPayFragment : Fragment(R.layout.fragment_welcome_to_dashpay) {
    private val binding by viewBinding(FragmentWelcomeToDashpayBinding::bind)
    private val requestUserNameViewModel by activityViewModels<RequestUserNameViewModel>()
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.titleBar.setNavigationOnClickListener { requireActivity().finish() }

        binding.welcomeDashpayContinueBtn.setOnClickListener {
            safeNavigate(WelcomeToDashPayFragmentDirections.welcomeToDashPayFragmentToUsernameVotingInfoFragment())
        }

        binding.balanceRequirementDisclaimer.text = getString(
            R.string.welcome_request_username_min_balance_disclaimer,
            requestUserNameViewModel.walletBalance.toPlainString(),
            Constants.DASH_PAY_FEE_CONTESTED.toPlainString()
        )
        binding.balanceRequirementDisclaimer.isVisible = !requestUserNameViewModel.canAffordIdentityCreation()
        binding.welcomeDashpayContinueBtn.isEnabled = requestUserNameViewModel.canAffordIdentityCreation()
    }
}
