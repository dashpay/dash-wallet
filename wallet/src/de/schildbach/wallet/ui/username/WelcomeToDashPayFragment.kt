package de.schildbach.wallet.ui.username

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentWelcomeToDashpayBinding
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.safeNavigate

@AndroidEntryPoint
@ExperimentalCoroutinesApi
class WelcomeToDashPayFragment : Fragment(R.layout.fragment_welcome_to_dashpay) {
    private val binding by viewBinding(FragmentWelcomeToDashpayBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.titleBar.setNavigationOnClickListener { findNavController().popBackStack() }

        binding.welcomeDashpayContinueBtn.setOnClickListener {
            safeNavigate(WelcomeToDashPayFragmentDirections.welcomeToDashPayFragmentToUsernameVotingInfoFragment())
        }
    }
}
