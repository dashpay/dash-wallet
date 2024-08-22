package de.schildbach.wallet.ui.username

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.graphics.drawable.AnimationDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Parcelable
import android.text.Editable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.StyleSpan
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.ui.dashpay.DashPayViewModel
import de.schildbach.wallet.ui.main.MainActivity
import de.schildbach.wallet.ui.username.voting.RequestUserNameViewModel
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentUsernameRegistrationBinding
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.dash.wallet.common.ui.viewBinding


@AndroidEntryPoint
@ExperimentalCoroutinesApi
class UsernameRegistrationFragment : Fragment(R.layout.fragment_username_registration) {

    companion object {
        const val CREATE_USER_NAME_ARGS = "CreateUsernameArgs"
    }
    private val binding by viewBinding(FragmentUsernameRegistrationBinding::bind)

    private val dashPayViewModel: DashPayViewModel by activityViewModels()
    private val requestUsernameViewModel: RequestUserNameViewModel by activityViewModels()
    private lateinit var walletApplication: WalletApplication

    private var reuseTransaction: Boolean = false
    private var useInvite: Boolean = false

    private var handler: Handler = Handler()
    private lateinit var checkUsernameNotExistRunnable: Runnable

    private var createUsernameArgs: CreateUsernameArgs? = null
    private val slideInAnimation by lazy { AnimationUtils.loadAnimation(requireContext(), R.anim.slide_in_bottom) }
    private val fadeOutAnimation by lazy { AnimationUtils.loadAnimation(requireContext(), R.anim.fade_out) }
    private lateinit var completeUsername: String
    private var isProcessing = false

    @SuppressLint("ResourceType")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.chooseUsernameTitle.text = getText(R.string.choose_your_username)
        binding.closeBtn.setOnClickListener {
            if (requestUsernameViewModel.isUsernameContestable()) {
                startActivity(MainActivity.createIntent(requireContext(), R.id.moreFragment))
            } else {
                // go to the home screen
                startActivity(MainActivity.createIntent(requireContext()))
            }
            requireActivity().finish()
        }
        binding.processingIdentityDismissBtn.setOnClickListener { requireActivity().finish() }

        initViewModel()
        walletApplication = requireActivity().application as WalletApplication

        // TODO: we probably don't need this
        createUsernameArgs = arguments?.getParcelable(CREATE_USER_NAME_ARGS)
        // why are the args not passed via the nav graph?
        // TODO: fix the passing of arguments or just use the dashPayViewModel
        if (createUsernameArgs == null)
            createUsernameArgs = dashPayViewModel.createUsernameArgs
        when (createUsernameArgs?.actions) {
            CreateUsernameActions.DISPLAY_COMPLETE -> {
                this.completeUsername = createUsernameArgs?.userName!!
                showCompleteState()
                doneAndDismiss()
            }
            CreateUsernameActions.REUSE_TRANSACTION -> {
                reuseTransaction = true
                //showKeyBoard()
            }
            CreateUsernameActions.FROM_INVITE -> {
                // don't show the keyboard if launched from invite
                useInvite = true
            }
            else -> {
                // not sure what we need to do here
            }
        }
    }

    private fun initViewModel() {
        dashPayViewModel.blockchainIdentity.observe(viewLifecycleOwner) {
            when {
                it?.creationStateErrorMessage != null -> {
                    requireActivity().finish()
                }

                it?.creationState == BlockchainIdentityData.CreationState.DONE ||
                        it?.creationState == BlockchainIdentityData.CreationState.VOTING -> {
                    completeUsername = it.username ?: ""
                    showCompleteState()
                }

                it?.creationState?.ordinal in BlockchainIdentityData.CreationState.UPGRADING_WALLET.ordinal until BlockchainIdentityData.CreationState.DONE.ordinal -> {
                    showProcessingState()
                }
            }
        }
    }

    private fun doneAndDismiss() {
        dashPayViewModel.usernameDoneAndDismiss()
    }

    private fun showCompleteState() {
        binding.processingIdentity.visibility = View.GONE
        binding.chooseUsernameTitle.visibility = View.GONE
        binding.orbitView.findViewById<View>(R.id.placeholder_user_icon).visibility = View.GONE
        binding.identityComplete.visibility = View.VISIBLE
        binding.orbitView.findViewById<View>(R.id.dashpay_user_icon).visibility = View.VISIBLE
        binding.orbitView.findViewById<TextView>(R.id.username_1st_letter).text = completeUsername[0].toString()

        val text = getString(R.string.identity_complete_message, completeUsername)

        val spannableContent = SpannableString(text)
        val start = text.indexOf(completeUsername)
        val end = start + completeUsername.length
        spannableContent.setSpan(StyleSpan(Typeface.BOLD), start, end, 0)
        binding.identityCompleteText.text = spannableContent
        binding.identityCompleteButton.setOnClickListener { requireActivity().finish() }
    }

    private fun showProcessingState() {
        if (!isProcessing) {
            val username = requestUsernameViewModel.requestedUserName!!
                val text = getString(if (requestUsernameViewModel.isUsernameContestable()) {
                    R.string.username_being_requested
                } else {
                    R.string.username_being_created
                }, username
            )

            binding.processingIdentity.visibility = View.VISIBLE
            binding.chooseUsernameTitle.startAnimation(fadeOutAnimation)
            binding.processingIdentity.startAnimation(slideInAnimation)
            (binding.processingIdentityLoadingImage.drawable as AnimationDrawable).start()

            val spannableContent = SpannableString(text)
            val start = text.indexOf(username)
            val end = start + username.length
            spannableContent.setSpan(StyleSpan(Typeface.BOLD), start, end, 0)
            binding.processingIdentityMessage.text = spannableContent
            isProcessing = true
        }
    }
}
