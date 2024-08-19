package de.schildbach.wallet.ui.username.voting

import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.ui.dashpay.DashPayViewModel
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentVerfiyIdentityBinding
import kotlinx.coroutines.launch
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.KeyboardUtil
import org.dash.wallet.common.util.observe
import org.dash.wallet.common.util.safeNavigate
import java.util.Date
import java.util.concurrent.TimeUnit


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
            requestUserNameViewModel.setRequestedUserNameLink(binding.linkInput.text.toString())
            requestUserNameViewModel.verify()
            lifecycleScope.launch {
                checkViewConfirmDialog()
            }
            //findNavController().popBackStack()
        }

        requestUserNameViewModel.uiState.observe(viewLifecycleOwner) {
//            if (it.usernameSubmittedSuccess) {
//                requireActivity().finish()
//            }

//            if (it.usernameSubmittedError) {
//                showErrorDialog()
//            }
//
//             if (it.usernameCharactersValid && it.usernameLengthValid && it.usernameCheckSuccess) {
//
//                if (it.usernameVerified) {
//                    hideKeyboard()
//                    checkViewConfirmDialog()
//                }
//            }
        }

        dashPayViewModel.blockchainIdentity.observe(viewLifecycleOwner) {
            if (it?.creationStateErrorMessage != null) {
                requireActivity().finish()
            } else if ((it?.creationState?.ordinal ?: 0) > BlockchainIdentityData.CreationState.NONE.ordinal) {
                safeNavigate(VerifyIdentityFragmentDirections.verifyToUsernameRegistrationFragment())
            }
        }
    }

    private fun hideKeyboard() {
        KeyboardUtil.hideKeyboard(requireContext(), view = binding.linkInput)
    }

    private suspend fun checkViewConfirmDialog() {
        // TODO: Can we cancel the request?
        if (requestUserNameViewModel.hasUserCancelledVerification()) {
            requestUserNameViewModel.submit()
        } else {
            safeNavigate(
                VerifyIdentityFragmentDirections.verifyIdentityFragmentToConfirmUsernameRequestDialog(
                    requestUserNameViewModel.requestedUserName!!
                )
            )
        }
    }
}
