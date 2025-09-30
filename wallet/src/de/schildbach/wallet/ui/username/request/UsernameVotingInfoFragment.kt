package de.schildbach.wallet.ui.username.request

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.dashpay.DashPayViewModel
import de.schildbach.wallet.ui.username.UsernameType
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
    private val args by navArgs<UsernameVotingInfoFragmentArgs>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.titleBar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        binding.usernameVotingInfoContinueBtn.setOnClickListener {
            if (!args.closeInstead) {
                safeNavigate(
                    UsernameVotingInfoFragmentDirections.usernameVotingInfoFragmentToRequestUsernameFragment(
                        usernameType = UsernameType.Primary
                    )
                )
            } else {
                findNavController().popBackStack()
            }
        }
        lifecycleScope.launchWhenStarted {
            lifecycleScope.launch {
                dashPayViewModel.setIsDashPayInfoShown(true)
            }
        }
    }
}
