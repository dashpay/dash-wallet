package de.schildbach.wallet.ui.username.voting

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.dashpay.DashPayViewModel
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentVerfiyIdentityBinding
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.KeyboardUtil


@AndroidEntryPoint
class VerifyIdentityFragment : Fragment(R.layout.fragment_verfiy_identity) {
    private val binding by viewBinding(FragmentVerfiyIdentityBinding::bind)
    private val dashPayViewModel: DashPayViewModel by activityViewModels()
    private val viewModel by viewModels<VerifyIdentityViewModel>()
    private val requestUserNameViewModel by activityViewModels<RequestUserNameViewModel>()
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        arguments?.getString("username")?.let {
            binding.postText.text = getString(R.string.please_vote_to_approve, it)
        }

        binding.copyPostTextBtn.setOnClickListener {
            viewModel.copyPost(binding.postText.text.toString())
            Toast.makeText(
                requireContext(),
                getString(org.dash.wallet.integrations.crowdnode.R.string.copied),
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.linkInput.doOnTextChanged { text, _, _, _ ->
            val username = text.toString()
            val isValidLink = username.isNotEmpty()
            binding.verifyBtn.isEnabled = isValidLink
            if (!text.isNullOrEmpty()) {
                binding.linkInputLayout.hint = getString(R.string.link)
            } else {
                binding.linkInputLayout.hint = getString(R.string.paste_the_link)
            }
        }

        binding.titleBar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }
        hideKeyboard()
        binding.verifyBtn.setOnClickListener {
            requestUserNameViewModel.verfiy()
            findNavController().popBackStack()
        }
    }

    private fun hideKeyboard() {
        KeyboardUtil.hideKeyboard(requireContext(), view = binding.linkInput)
    }
}
