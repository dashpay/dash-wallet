package de.schildbach.wallet.ui.username.request

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet.ui.shielded.ShieldedBalanceActivity
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentWelcomeToDashpayBinding
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.observe
import org.dash.wallet.common.util.safeNavigate

/**
 * "Welcome to Dash Pay" (Figma 1855:11660). Continue runs the shielded-funds
 * payment decision (flow canvas 555:811): with usable shielded funds the
 * "Select your payment option" sheet is shown, without them the "Make your
 * username private" sheet — and with shielded balances unavailable (flag
 * off, platform unsupported, or an invite paying the fee) the flow proceeds
 * directly, exactly as before the sheets existed.
 */
@AndroidEntryPoint
class WelcomeToDashPayFragment : Fragment(R.layout.fragment_welcome_to_dashpay) {
    private val binding by viewBinding(FragmentWelcomeToDashpayBinding::bind)
    private val requestUserNameViewModel by activityViewModels<RequestUserNameViewModel>()
    private val paymentViewModel by activityViewModels<UsernamePaymentViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.titleBar.setNavigationOnClickListener { requireActivity().finish() }

        binding.welcomeDashpayContinueBtn.setOnClickListener {
            onContinue()
        }

        childFragmentManager.setFragmentResultListener(
            UsernamePaymentDialogFragment.REQUEST_KEY,
            viewLifecycleOwner
        ) { _, result ->
            when (result.getString(UsernamePaymentDialogFragment.RESULT_ACTION)) {
                UsernamePaymentDialogFragment.ACTION_CONTINUE -> {
                    val source = result.getString(UsernamePaymentDialogFragment.RESULT_SOURCE)
                        ?.let { runCatching { UsernamePaymentSource.valueOf(it) }.getOrNull() }
                        ?: UsernamePaymentSource.DASH_BALANCE
                    requestUserNameViewModel.paymentSource = source
                    continueToFlow()
                }
                UsernamePaymentDialogFragment.ACTION_SHIELD_FIRST -> {
                    // The user shields funds in the internal-transfer flow and
                    // comes back to this screen to continue (a terminal transfer
                    // outcome, though, leaves straight to home — shieldFirst).
                    startActivity(
                        ShieldedBalanceActivity.createIntent(requireContext(), shieldFirst = true)
                    )
                }
            }
        }

        requestUserNameViewModel.walletBalance.observe(viewLifecycleOwner) {
            updateView()
        }
        requestUserNameViewModel.identityBalance.observe(viewLifecycleOwner) {
            updateView()
        }
        paymentViewModel.uiState.observe(viewLifecycleOwner) {
            updateView()
        }
    }

    private fun onContinue() {
        val prompt = if (Constants.SUPPORTS_PLATFORM && !requestUserNameViewModel.isUsingInvite()) {
            paymentViewModel.uiState.value.prompt
        } else {
            UsernamePaymentPrompt.NONE
        }

        if (prompt == UsernamePaymentPrompt.NONE) {
            requestUserNameViewModel.paymentSource = UsernamePaymentSource.DASH_BALANCE
            continueToFlow()
        } else if (childFragmentManager.findFragmentByTag(UsernamePaymentDialogFragment.TAG) == null) {
            UsernamePaymentDialogFragment.newInstance(prompt)
                .show(childFragmentManager, UsernamePaymentDialogFragment.TAG)
        }
    }

    private fun continueToFlow() {
        safeNavigate(WelcomeToDashPayFragmentDirections.welcomeToDashPayFragmentToUsernameVotingInfoFragment())
    }

    fun updateView() {
        // The username fee can be paid from the L1 wallet balance OR (new
        // with the shielded designs) from a trustworthy shielded balance
        // that covers it — the CoinJoin mixed-balance sourcing this
        // replaces is gone.
        val canPayFromShielded = paymentViewModel.uiState.value.canPayFeeFromShielded
        if (!requestUserNameViewModel.isUsingInvite()) {
            if (!requestUserNameViewModel.canAffordNonContestedUsername() && !canPayFromShielded) {
                binding.balanceRequirementDisclaimer.text = getString(
                    R.string.welcome_request_username_min_balance_disclaimer_noncontested,
                    Constants.DASH_PAY_FEE.toPlainString()
                )
            } else if (!requestUserNameViewModel.canAffordContestedUsername()) {
                // "Cost up to" must quote the true maximum COST of a
                // username — the 0.25 contested fee/denomination
                // (DASH_PAY_FEE_CONTESTED), identical on both payment
                // paths. The padded shield-first funding guidance
                // (denomination + fee margin, SHIELDED_USERNAME_FUND_MIN*)
                // is NOT a cost; the shield sheets present it as "shield at
                // least X" instead.
                binding.balanceRequirementDisclaimer.text = getString(
                    R.string.welcome_request_username_min_balance_disclaimer_all,
                    requestUserNameViewModel.walletBalance.value.toPlainString(),
                    Constants.DASH_PAY_FEE_CONTESTED.toPlainString()
                )
            }
            binding.balanceRequirementDisclaimer.isVisible =
                !requestUserNameViewModel.canAffordContestedUsername()
            binding.welcomeDashpayContinueBtn.isEnabled =
                requestUserNameViewModel.canAffordNonContestedUsername() || canPayFromShielded
        } else {
            binding.welcomeDashpayContinueBtn.isEnabled = true
        }
    }
}
