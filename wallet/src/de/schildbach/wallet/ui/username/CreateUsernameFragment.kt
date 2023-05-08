package de.schildbach.wallet.ui.username

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
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModelProvider
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.BlockchainIdentityData
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.SetPinActivity
import de.schildbach.wallet.ui.dashpay.CreateIdentityService
import de.schildbach.wallet.ui.dashpay.DashPayViewModel
import de.schildbach.wallet.ui.dashpay.PlatformPaymentConfirmDialog
import de.schildbach.wallet.ui.invite.OnboardFromInviteActivity
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentCreateUsernameBinding
import kotlinx.android.parcel.Parcelize
import kotlinx.android.synthetic.main.users_orbit.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.dash.wallet.common.InteractionAwareActivity
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.KeyboardUtil
import org.dash.wallet.common.util.safeNavigate

enum class CreateUsernameActions {
    CREATE_NEW,
    DISPLAY_COMPLETE,
    REUSE_TRANSACTION,
    FROM_INVITE,
    FROM_INVITE_REUSE_TRANSACTION,
}

@Parcelize
data class CreateUsernameArgs(
    val actions: CreateUsernameActions? = null,
    val userName: String? = null,
    val invite: InvitationLinkData? = null,
    val fromOnboardng: Boolean = false,
) : Parcelable

@AndroidEntryPoint
@ExperimentalCoroutinesApi
class CreateUsernameFragment : Fragment(R.layout.fragment_create_username), TextWatcher {

    companion object {
        const val CREATE_USER_NAME_ARGS = "CreateUsernameArgs"
    }
    private val binding by viewBinding(FragmentCreateUsernameBinding::bind)

    private val dashPayViewModel: DashPayViewModel by activityViewModels()
    private lateinit var walletApplication: WalletApplication

    private var reuseTransaction: Boolean = false
    private var useInvite: Boolean = false

    private var handler: Handler = Handler()
    private lateinit var checkUsernameNotExistRunnable: Runnable

    private var createUsernameArgs: CreateUsernameArgs? = null

    private val regularTypeFace by lazy { ResourcesCompat.getFont(requireContext(), R.font.inter_regular) }
    private val mediumTypeFace by lazy { ResourcesCompat.getFont(requireContext(), R.font.inter_medium) }
    private val slideInAnimation by lazy { AnimationUtils.loadAnimation(requireContext(), R.anim.slide_in_bottom) }
    private val fadeOutAnimation by lazy { AnimationUtils.loadAnimation(requireContext(), R.anim.fade_out) }
    private lateinit var completeUsername: String

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.chooseUsernameTitle.text = getText(R.string.choose_your_username)
        binding.closeBtn.setOnClickListener { requireActivity().finish() }
        binding.username.addTextChangedListener(this)
        binding.registerBtn.setOnClickListener {
            safeNavigate(CreateUsernameFragmentDirections.createUsernameToUsernamePrivacy())
//            if (reuseTransaction) {
//                triggerIdentityCreation(true)
//            } else {
//                showConfirmationDialog()
//            }
        }
        binding.processingIdentityDismissBtn.setOnClickListener { requireActivity().finish() }

        initViewModel()
        walletApplication = requireActivity().application as WalletApplication

        createUsernameArgs = arguments?.getParcelable(CREATE_USER_NAME_ARGS)
        when (createUsernameArgs?.actions) {
            CreateUsernameActions.DISPLAY_COMPLETE -> {
                this.completeUsername = createUsernameArgs?.userName!!
                showCompleteState()
                doneAndDismiss()
            }
            CreateUsernameActions.REUSE_TRANSACTION -> {
                reuseTransaction = true
                showKeyBoard()
            }
            CreateUsernameActions.FROM_INVITE -> {
                // don't show the keyboard if launched from invite
                useInvite = true
            }
        }
    }

    private fun showKeyBoard() {
        handler.postDelayed({
            KeyboardUtil.showSoftKeyboard(requireContext(), binding.username)
        }, 1333)
    }

    private fun initViewModel() {
        val confirmTransactionSharedViewModel = ViewModelProvider(this).get(
            PlatformPaymentConfirmDialog.SharedViewModel::class.java,
        )
        confirmTransactionSharedViewModel.clickConfirmButtonEvent.observe(viewLifecycleOwner) {
            if (createUsernameArgs?.actions == CreateUsernameActions.FROM_INVITE) {
                triggerIdentityCreationFromInvite(reuseTransaction)
            } else {
                triggerIdentityCreation(false)
            }
        }

        dashPayViewModel.getUsernameLiveData.observe(viewLifecycleOwner) {
            (requireActivity() as? InteractionAwareActivity)?.imitateUserInteraction()
            when (it.status) {
                Status.LOADING -> {
                    // this is delayed by the logic of checkUsernameNotExist(...) method,
                    // therefore the UI state is configured before calling it using usernameAvailabilityValidationInProgressState()
                }
                Status.CANCELED -> {
                    // no need to do anything
                }
                Status.ERROR -> {
                    usernameAvailabilityValidationErrorState()
                }
                Status.SUCCESS -> {
                    if (it.data != null) {
                        // This user name exists
                        usernameAvailabilityValidationTakenState()
                    } else {
                        usernameAvailabilityValidationAvailableState()
                    }
                }
            }
        }
    }

    private fun usernameAvailabilityValidationInProgressState() {
        binding.usernameExistsReq.visibility = View.VISIBLE
        binding.usernameExistsReqLabel.visibility = View.VISIBLE
        binding.usernameExistsReqProgress.visibility = View.VISIBLE
        binding.usernameExistsReqImg.visibility = View.INVISIBLE
        binding.usernameExistsReqLabel.visibility = View.VISIBLE
        binding.usernameExistsReqLabel.typeface = regularTypeFace
        binding.usernameExistsReqLabel.setTextColor(ResourcesCompat.getColor(resources, R.color.dark_text, null))
        binding.usernameExistsReqLabel.setText(R.string.identity_username_validating)
        binding.registerBtn.isEnabled = false
    }

    private fun usernameAvailabilityValidationErrorState() {
        binding.usernameExistsReqProgress.visibility = View.INVISIBLE
        binding.usernameExistsReqImg.visibility = View.VISIBLE
        binding.usernameExistsReqImg.setImageResource(R.drawable.ic_username_requirement_x)
        binding.usernameExistsReqLabel.typeface = mediumTypeFace
        binding.usernameExistsReqLabel.setTextColor(ResourcesCompat.getColor(resources, R.color.dash_red, null))
        binding.usernameExistsReqLabel.setText(R.string.platform_communication_error)
        binding.registerBtn.isEnabled = false
    }

    private fun usernameAvailabilityValidationTakenState() {
        binding.usernameExistsReqProgress.visibility = View.INVISIBLE
        binding.usernameExistsReqImg.visibility = View.VISIBLE
        binding.usernameExistsReqImg.setImageResource(R.drawable.ic_username_requirement_x)
        binding.usernameExistsReqLabel.typeface = mediumTypeFace
        binding.usernameExistsReqLabel.setTextColor(ResourcesCompat.getColor(resources, R.color.dash_red, null))
        binding.usernameExistsReqLabel.setText(R.string.identity_username_taken)
        binding.registerBtn.isEnabled = false
    }

    private fun usernameAvailabilityValidationAvailableState() {
        binding.usernameExistsReqProgress.visibility = View.INVISIBLE
        binding.usernameExistsReqImg.visibility = View.VISIBLE
        binding.usernameExistsReqImg.setImageResource(R.drawable.ic_username_requirement_checkmark)
        binding.usernameExistsReqLabel.typeface = mediumTypeFace
        binding.usernameExistsReqLabel.setTextColor(ResourcesCompat.getColor(resources, R.color.dark_text, null))
        binding.usernameExistsReqLabel.setText(R.string.identity_username_available)
        binding.registerBtn.isEnabled = true
    }

    private fun triggerIdentityCreation(reuseTransaction: Boolean) {
        val username = binding.username.text.toString()
        if (reuseTransaction) {
            requireActivity().startService(CreateIdentityService.createIntentForNewUsername(requireContext(), username))
            requireActivity().finish()
        } else {
            AppDatabase.getAppDatabase().blockchainIdentityDataDaoAsync().loadBase().observe(viewLifecycleOwner) {
                if (it?.creationStateErrorMessage != null && !reuseTransaction) {
                    requireActivity().finish()
                } else if (it?.creationState == BlockchainIdentityData.CreationState.DONE) {
                    completeUsername = it.username ?: ""
                    showCompleteState()
                }
            }
            showProcessingState()
            requireActivity().startService(CreateIdentityService.createIntent(requireContext(), username))
        }
    }

    private fun triggerIdentityCreationFromInvite(reuseTransaction: Boolean) {
        val username = binding.username.text.toString()
        if (reuseTransaction) {
            requireActivity().startService(CreateIdentityService.createIntentFromInviteForNewUsername(requireContext(), username))
            requireActivity().finish()
        } else {
            val fromOnboarding = createUsernameArgs?.fromOnboardng ?: false
            if (fromOnboarding) {
                walletApplication.configuration.onboardingInviteUsername = username
                val goNextIntent = SetPinActivity.createIntent(requireActivity().application, R.string.set_pin_create_new_wallet, false, null, onboardingInvite = true)
                startActivity(OnboardFromInviteActivity.createIntent(requireContext(), OnboardFromInviteActivity.Mode.STEP_2, goNextIntent))
                requireActivity().finish()
                return
            } else {
                AppDatabase.getAppDatabase().blockchainIdentityDataDaoAsync().loadBase().observe(viewLifecycleOwner) {
                    if (it?.creationStateErrorMessage != null && !reuseTransaction) {
                        requireActivity().finish()
                    } else if (it?.creationState == BlockchainIdentityData.CreationState.DONE) {
                        completeUsername = it.username ?: ""
                        showCompleteState()
                    }
                }
                showProcessingState()
                createUsernameArgs?.invite?.let {
                    requireActivity().startService(CreateIdentityService.createIntentFromInvite(requireContext(), username, it))
                }
            }
        }
        showProcessingState()
    }

    private fun doneAndDismiss() {
        dashPayViewModel.usernameDoneAndDismiss()
    }

    private fun showCompleteState() {
        binding.registrationContent.visibility = View.GONE
        binding.processingIdentity.visibility = View.GONE
        binding.chooseUsernameTitle.visibility = View.GONE
        placeholder_user_icon.visibility = View.GONE
        binding.identityComplete.visibility = View.VISIBLE
        dashpay_user_icon.visibility = View.VISIBLE
        username_1st_letter.text = completeUsername[0].toString()

        val text = getString(R.string.identity_complete_message, completeUsername)

        val spannableContent = SpannableString(text)
        val start = text.indexOf(completeUsername)
        val end = start + completeUsername.length
        spannableContent.setSpan(StyleSpan(Typeface.BOLD), start, end, 0)
        binding.identityCompleteText.text = spannableContent
        binding.identityCompleteButton.setOnClickListener { requireActivity().finish() }
    }

    private fun validateUsernameSize(uname: String): Boolean {
        val lengthValid = uname.length in Constants.USERNAME_MIN_LENGTH..Constants.USERNAME_MAX_LENGTH

        binding.minCharsReqImg.visibility = if (uname.isNotEmpty()) {
            binding.minCharsReqLabel.typeface = mediumTypeFace
            View.VISIBLE
        } else {
            binding.minCharsReqLabel.typeface = regularTypeFace
            View.INVISIBLE
        }

        if (lengthValid) {
            binding.minCharsReqImg.setImageResource(R.drawable.ic_username_requirement_checkmark)
        } else {
            binding.minCharsReqImg.setImageResource(R.drawable.ic_username_requirement_x)
        }

        return lengthValid
    }

    private fun validateUsernameCharacters(uname: String): Boolean {
        val alphaNumHyphenValid = !Regex("[^a-zA-Z0-9\\-]").containsMatchIn(uname)
        val startOrEndWithHyphen = uname.startsWith("-") || uname.endsWith("-")
        val containsHyphen = uname.contains("-")

        binding.alphanumReqImg.visibility = if (uname.isNotEmpty() || !alphaNumHyphenValid) {
            binding.alphanumReqLabel.typeface = mediumTypeFace
            View.VISIBLE
        } else {
            binding.alphanumReqLabel.typeface = regularTypeFace
            View.INVISIBLE
        }

        if (alphaNumHyphenValid) {
            binding.alphanumReqImg.setImageResource(R.drawable.ic_username_requirement_checkmark)
        } else {
            binding.alphanumReqImg.setImageResource(R.drawable.ic_username_requirement_x)
        }

        if (containsHyphen) {
            binding.hyphenReqImg.visibility = View.VISIBLE
            binding.hyphenReqLabel.visibility = View.VISIBLE
            if (!startOrEndWithHyphen) {
                // leave isValid with the same value that is already has (same as isValid && true)
                binding.hyphenReqImg.setImageResource(R.drawable.ic_username_requirement_checkmark)
                binding.hyphenReqLabel.typeface = mediumTypeFace
            } else {
                binding.hyphenReqImg.setImageResource(R.drawable.ic_username_requirement_x)
                binding.hyphenReqLabel.typeface = regularTypeFace
            }
        } else {
            binding.hyphenReqImg.visibility = View.GONE
            binding.hyphenReqLabel.visibility = View.GONE
        }

        return alphaNumHyphenValid && !startOrEndWithHyphen
    }

    private fun checkUsernameNotExist(username: String) {
        if (this::checkUsernameNotExistRunnable.isInitialized) {
            handler.removeCallbacks(checkUsernameNotExistRunnable)
        }
        checkUsernameNotExistRunnable = Runnable {
            dashPayViewModel.searchUsername(username)
        }
        handler.postDelayed(checkUsernameNotExistRunnable, 600)
    }

    override fun afterTextChanged(s: Editable?) {
        val username = s?.toString()

        if (username != null) {
            var usernameIsValid = validateUsernameCharacters(username) and validateUsernameSize(username) // force validateUsernameSize to execute

            if (usernameIsValid) { // ensure username meets basic rules before making a Platform query
                usernameAvailabilityValidationInProgressState()
                checkUsernameNotExist(username)
            } else {
                binding.usernameExistsReqProgress.visibility = View.INVISIBLE
                binding.usernameExistsReqLabel.visibility = View.GONE
                binding.usernameExistsReqImg.visibility = View.GONE
                binding.registerBtn.isEnabled = false
                if (this::checkUsernameNotExistRunnable.isInitialized) {
                    handler.removeCallbacks(checkUsernameNotExistRunnable)
                    dashPayViewModel.searchUsername(null)
                }
            }
        }
        (requireActivity() as? InteractionAwareActivity)?.imitateUserInteraction()
    }

    private fun showProcessingState() {
        val username = binding.username.text.toString()
        val text = getString(R.string.username_being_created, username)

        binding.processingIdentity.visibility = View.VISIBLE
        binding.registrationContent.visibility = View.GONE
        binding.chooseUsernameTitle.startAnimation(fadeOutAnimation)
        binding.processingIdentity.startAnimation(slideInAnimation)
        (binding.processingIdentityLoadingImage.drawable as AnimationDrawable).start()

        val spannableContent = SpannableString(text)
        val start = text.indexOf(username)
        val end = start + binding.username.length()
        spannableContent.setSpan(StyleSpan(Typeface.BOLD), start, end, 0)
        binding.processingIdentityMessage.text = spannableContent
    }

    private fun showConfirmationDialog() {
        val upgradeFee = if (createUsernameArgs?.actions == CreateUsernameActions.FROM_INVITE) null else Constants.DASH_PAY_FEE
        val username = "<b>“${binding. username.text}”</b>"
        val dialogMessage = getString(R.string.new_account_confirm_message, username)
        val dialogTitle = getString(R.string.dashpay_upgrade_fee)
        val dialog = PlatformPaymentConfirmDialog.createDialog(dialogTitle, dialogMessage, upgradeFee, createUsernameArgs?.invite != null)
        dialog.show(requireActivity().supportFragmentManager, "NewAccountConfirmDialog")
    }

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
    }

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
    }
}
