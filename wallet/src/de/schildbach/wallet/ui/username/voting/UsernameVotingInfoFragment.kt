package de.schildbach.wallet.ui.username.voting

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet.ui.dashpay.DashPayViewModel
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentUsernameVotingInfoBinding
import kotlinx.coroutines.launch
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.safeNavigate

@AndroidEntryPoint
class UsernameVotingInfoFragment : Fragment(R.layout.fragment_username_voting_info) {
    private val binding by viewBinding(FragmentUsernameVotingInfoBinding::bind)
    private val dashPayViewModel: DashPayViewModel by activityViewModels()
    private val requestUserNameViewModel by activityViewModels<RequestUserNameViewModel>()
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.titleBar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        binding.usernameVotingInfoContinueBtn.setOnClickListener {
            safeNavigate(
                UsernameVotingInfoFragmentDirections.usernameVotingInfoFragmentToRequestUsernameFragment()
            )
        }
        lifecycleScope.launchWhenStarted {
            lifecycleScope.launch {
                dashPayViewModel.setIsDashPayInfoShown(true)
            }
        }
    }
}
