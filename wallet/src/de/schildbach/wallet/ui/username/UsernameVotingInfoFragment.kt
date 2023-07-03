package de.schildbach.wallet.ui.username

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentUsernameVotingInfoBinding
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.dash.wallet.common.ui.viewBinding

@AndroidEntryPoint
@ExperimentalCoroutinesApi
class UsernameVotingInfoFragment : Fragment(R.layout.fragment_username_voting_info) {
    private val binding by viewBinding(FragmentUsernameVotingInfoBinding::bind)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.titleBar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        binding.usernameVotingInfoContinueBtn.setOnClickListener {
            findNavController().popBackStack(
                findNavController().graph.findStartDestination().id,
                false
            )
        }
    }
}
