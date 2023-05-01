/*
 * Copyright 2020 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.AnimationDrawable
import android.os.Bundle
import android.os.Handler
import android.text.Editable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.StyleSpan
import android.view.View
import android.view.animation.AnimationUtils
import androidx.activity.viewModels
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants.USERNAME_MAX_LENGTH
import de.schildbach.wallet.Constants.USERNAME_MIN_LENGTH
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.entity.BlockchainIdentityBaseData
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.database.dao.BlockchainIdentityDataDao
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.dashpay.CreateIdentityService
import de.schildbach.wallet.ui.dashpay.DashPayViewModel
import de.schildbach.wallet.ui.dashpay.PlatformPaymentConfirmDialog
import de.schildbach.wallet.ui.invite.OnboardFromInviteActivity
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_create_username.*
import kotlinx.android.synthetic.main.users_orbit.*
import org.bitcoinj.core.Coin
import org.dash.wallet.common.InteractionAwareActivity
import org.dash.wallet.common.util.KeyboardUtil
import org.slf4j.LoggerFactory
import javax.inject.Inject

@AndroidEntryPoint
class CreateUsernameActivity : InteractionAwareActivity(), TextWatcher {

    private val regularTypeFace by lazy { ResourcesCompat.getFont(this, R.font.inter_regular) }
    private val mediumTypeFace by lazy { ResourcesCompat.getFont(this, R.font.inter_medium) }
    private val slideInAnimation by lazy { AnimationUtils.loadAnimation(this, R.anim.slide_in_bottom) }
    private val fadeOutAnimation by lazy { AnimationUtils.loadAnimation(this, R.anim.fade_out) }
    private lateinit var completeUsername: String

    private val invite by lazy {
        intent.getParcelableExtra<InvitationLinkData>(EXTRA_INVITE)
    }

    private val dashPayViewModel by viewModels<DashPayViewModel>()

    //    val viewModel by lazy {
//        ViewModelProvider(this).get(InviteFriendViewModel::class.java)
//    }
    @Inject
    lateinit var walletApplication: WalletApplication
    @Inject
    lateinit var blockchainIdentityDataDao: BlockchainIdentityDataDao

    private var reuseTransaction: Boolean = false
    private var useInvite: Boolean = false

    private var handler: Handler = Handler()
    private lateinit var checkUsernameNotExistRunnable: Runnable

    companion object {
        private val log = LoggerFactory.getLogger(CreateUsernameActivity::class.java)

        private const val ACTION_CREATE_NEW = "action_create_new"
        private const val ACTION_DISPLAY_COMPLETE = "action_display_complete"
        private const val ACTION_REUSE_TRANSACTION = "action_reuse_transaction"
        private const val ACTION_FROM_INVITE = "action_from_invite"
        private const val ACTION_FROM_INVITE_REUSE_TRANSACTION = "action_from_invite_reuse_transaction"

        private const val EXTRA_USERNAME = "extra_username"
        private const val EXTRA_INVITE = "extra_invite"
        private const val EXTRA_FROM_ONBOARDING = "extra_from_onboarding"

        @JvmStatic
        fun createIntent(context: Context, username: String? = null): Intent {
            return Intent(context, CreateUsernameActivity::class.java).apply {
                action = if (username == null) ACTION_CREATE_NEW else ACTION_DISPLAY_COMPLETE
                putExtra(EXTRA_USERNAME, username)
            }
        }

        @JvmStatic
        fun createIntentReuseTransaction(context: Context, blockchainIdentityData: BlockchainIdentityBaseData): Intent {
            return Intent(context, CreateUsernameActivity::class.java).apply {
                action = if (blockchainIdentityData.usingInvite) ACTION_FROM_INVITE_REUSE_TRANSACTION else ACTION_REUSE_TRANSACTION
            }
        }

        @JvmStatic
        fun createIntentFromInvite(context: Context, invite: InvitationLinkData, fromOnboarding: Boolean): Intent {
            return Intent(context, CreateUsernameActivity::class.java).apply {
                action = ACTION_FROM_INVITE
                putExtra(EXTRA_INVITE, invite)
                putExtra(EXTRA_FROM_ONBOARDING, fromOnboarding)
            }
        }

        @JvmStatic
        fun createIntentFromInviteReuseTransaction(context: Context): Intent {
            return Intent(context, CreateUsernameActivity::class.java).apply {
                action = ACTION_FROM_INVITE_REUSE_TRANSACTION
            }
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_create_username)

        choose_username_title.text = getText(R.string.choose_your_username)
        close_btn.setOnClickListener { finish() }
        username.addTextChangedListener(this)
        register_btn.setOnClickListener {
            if (reuseTransaction) {
                triggerIdentityCreation(true)
            } else {
                showConfirmationDialog()
            }
        }
        processing_identity_dismiss_btn.setOnClickListener { finish() }

        initViewModel()

        when (intent?.action) {
            ACTION_DISPLAY_COMPLETE -> {
                this.completeUsername = intent!!.extras!!.getString(EXTRA_USERNAME)!!
                showCompleteState()
                doneAndDismiss()
            }
            ACTION_REUSE_TRANSACTION -> {
                reuseTransaction = true
                showKeyBoard()
            }
            ACTION_FROM_INVITE -> {
                // don't show the keyboard if launched from invite
                useInvite = true
            }
        }
    }

    private fun showKeyBoard() {
        handler.postDelayed({
            KeyboardUtil.showSoftKeyboard(this@CreateUsernameActivity, username)
        }, 1333)
    }

    private fun initViewModel() {
        val confirmTransactionSharedViewModel = ViewModelProvider(this).get(PlatformPaymentConfirmDialog.SharedViewModel::class.java)
        confirmTransactionSharedViewModel.clickConfirmButtonEvent.observe(this, Observer {
            if (intent.action == ACTION_FROM_INVITE) {
                triggerIdentityCreationFromInvite(reuseTransaction)
            } else {
                triggerIdentityCreation(false)
            }
        })

        dashPayViewModel.getUsernameLiveData.observe(this, Observer {
            imitateUserInteraction()
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
        })
    }

    private fun usernameAvailabilityValidationInProgressState() {
        username_exists_req.visibility = View.VISIBLE
        username_exists_req_label.visibility = View.VISIBLE
        username_exists_req_progress.visibility = View.VISIBLE
        username_exists_req_img.visibility = View.INVISIBLE
        username_exists_req_label.visibility = View.VISIBLE
        username_exists_req_label.typeface = regularTypeFace
        username_exists_req_label.setTextColor(ResourcesCompat.getColor(resources, R.color.content_primary, null))
        username_exists_req_label.setText(R.string.identity_username_validating)
        register_btn.isEnabled = false
    }

    private fun usernameAvailabilityValidationErrorState() {
        username_exists_req_progress.visibility = View.INVISIBLE
        username_exists_req_img.visibility = View.VISIBLE
        username_exists_req_img.setImageResource(R.drawable.ic_username_requirement_x)
        username_exists_req_label.typeface = mediumTypeFace
        username_exists_req_label.setTextColor(ResourcesCompat.getColor(resources, R.color.dash_red, null))
        username_exists_req_label.setText(R.string.platform_communication_error)
        register_btn.isEnabled = false
    }

    private fun usernameAvailabilityValidationTakenState() {
        username_exists_req_progress.visibility = View.INVISIBLE
        username_exists_req_img.visibility = View.VISIBLE
        username_exists_req_img.setImageResource(R.drawable.ic_username_requirement_x)
        username_exists_req_label.typeface = mediumTypeFace
        username_exists_req_label.setTextColor(ResourcesCompat.getColor(resources, R.color.dash_red, null))
        username_exists_req_label.setText(R.string.identity_username_taken)
        register_btn.isEnabled = false
    }

    private fun usernameAvailabilityValidationAvailableState() {
        username_exists_req_progress.visibility = View.INVISIBLE
        username_exists_req_img.visibility = View.VISIBLE
        username_exists_req_img.setImageResource(R.drawable.ic_username_requirement_checkmark)
        username_exists_req_label.typeface = mediumTypeFace
        username_exists_req_label.setTextColor(ResourcesCompat.getColor(resources, R.color.content_primary, null))
        username_exists_req_label.setText(R.string.identity_username_available)
        register_btn.isEnabled = true
    }

    private fun triggerIdentityCreation(reuseTransaction: Boolean) {
        val username = username.text.toString()
        if (reuseTransaction) {
            startService(CreateIdentityService.createIntentForNewUsername(this, username))
            finish()
        } else {
            dashPayViewModel.blockchainIdentity.observe(this) {
                if (it?.creationStateErrorMessage != null) {
                    finish()
                } else if (it?.creationState == BlockchainIdentityData.CreationState.DONE) {
                    completeUsername = it.username ?: ""
                    showCompleteState()
                }
            }
            showProcessingState()
            startService(CreateIdentityService.createIntent(this, username))
        }
    }

    private fun triggerIdentityCreationFromInvite(reuseTransaction: Boolean) {
        val username = username.text.toString()
        if (reuseTransaction) {
            startService(CreateIdentityService.createIntentFromInviteForNewUsername(this, username))
            finish()
        } else {
            val fromOnboarding = intent.getBooleanExtra(EXTRA_FROM_ONBOARDING, false)
            if (fromOnboarding) {
                walletApplication.configuration.onboardingInviteUsername = username
                val goNextIntent = SetPinActivity.createIntent(application, R.string.set_pin_create_new_wallet, false, null, onboardingInvite = true)
                startActivity(OnboardFromInviteActivity.createIntent(this, OnboardFromInviteActivity.Mode.STEP_2, goNextIntent))
                finish()
                return
            } else {
                dashPayViewModel.blockchainIdentity.observe(this) {
                    if (it?.creationStateErrorMessage != null && !reuseTransaction) {
                        finish()
                    } else if (it?.creationState == BlockchainIdentityData.CreationState.DONE) {
                        completeUsername = it.username ?: ""
                        showCompleteState()
                    }
                }
                showProcessingState()
                invite?.let {
                    startService(CreateIdentityService.createIntentFromInvite(this, username, it))
                }
            }
        }
        showProcessingState()
    }

    private fun doneAndDismiss() {
        dashPayViewModel.usernameDoneAndDismiss()
    }

    private fun showCompleteState() {
        registration_content.visibility = View.GONE
        processing_identity.visibility = View.GONE
        choose_username_title.visibility = View.GONE
        placeholder_user_icon.visibility = View.GONE
        identity_complete.visibility = View.VISIBLE
        dashpay_user_icon.visibility = View.VISIBLE
        username_1st_letter.text = completeUsername[0].toString()

        val text = getString(R.string.identity_complete_message, completeUsername)

        val spannableContent = SpannableString(text)
        val start = text.indexOf(completeUsername)
        val end = start + completeUsername.length
        spannableContent.setSpan(StyleSpan(Typeface.BOLD), start, end, 0)
        identity_complete_text.text = spannableContent
        identity_complete_button.setOnClickListener { finish() }
    }

    private fun validateUsernameSize(uname: String): Boolean {
        val lengthValid = uname.length in USERNAME_MIN_LENGTH..USERNAME_MAX_LENGTH

        min_chars_req_img.visibility = if (uname.isNotEmpty()) {
            min_chars_req_label.typeface = mediumTypeFace
            View.VISIBLE
        } else {
            min_chars_req_label.typeface = regularTypeFace
            View.INVISIBLE
        }

        if (lengthValid) {
            min_chars_req_img.setImageResource(R.drawable.ic_username_requirement_checkmark)
        } else {
            min_chars_req_img.setImageResource(R.drawable.ic_username_requirement_x)
        }

        return lengthValid
    }

    private fun validateUsernameCharacters(uname: String): Boolean {
        val alphaNumHyphenValid = !Regex("[^a-zA-Z0-9\\-]").containsMatchIn(uname)
        val startOrEndWithHyphen = uname.startsWith("-") || uname.endsWith("-")
        val containsHyphen = uname.contains("-")

        alphanum_req_img.visibility = if (uname.isNotEmpty() || !alphaNumHyphenValid) {
            alphanum_req_label.typeface = mediumTypeFace
            View.VISIBLE
        } else {
            alphanum_req_label.typeface = regularTypeFace
            View.INVISIBLE
        }

        if (alphaNumHyphenValid) {
            alphanum_req_img.setImageResource(R.drawable.ic_username_requirement_checkmark)
        } else {
            alphanum_req_img.setImageResource(R.drawable.ic_username_requirement_x)
        }

        if (containsHyphen) {
            hyphen_req_img.visibility = View.VISIBLE
            hyphen_req_label.visibility = View.VISIBLE
            if (!startOrEndWithHyphen) {
                // leave isValid with the same value that is already has (same as isValid && true)
                hyphen_req_img.setImageResource(R.drawable.ic_username_requirement_checkmark)
                hyphen_req_label.typeface = mediumTypeFace
            } else {
                hyphen_req_img.setImageResource(R.drawable.ic_username_requirement_x)
                hyphen_req_label.typeface = regularTypeFace
            }
        } else {
            hyphen_req_img.visibility = View.GONE
            hyphen_req_label.visibility = View.GONE
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
            var usernameIsValid = validateUsernameCharacters(username) and validateUsernameSize(username) //force validateUsernameSize to execute

            if (usernameIsValid) {//ensure username meets basic rules before making a Platform query
                usernameAvailabilityValidationInProgressState()
                checkUsernameNotExist(username)
            } else {
                username_exists_req_progress.visibility = View.INVISIBLE
                username_exists_req_label.visibility = View.GONE
                username_exists_req_img.visibility = View.GONE
                register_btn.isEnabled = false
                if (this::checkUsernameNotExistRunnable.isInitialized) {
                    handler.removeCallbacks(checkUsernameNotExistRunnable)
                    dashPayViewModel.searchUsername(null)
                }
            }
        }
        imitateUserInteraction()
    }

    private fun showProcessingState() {
        val username = username.text.toString()
        val text = getString(R.string.username_being_created, username)

        processing_identity.visibility = View.VISIBLE
        registration_content.visibility = View.GONE
        choose_username_title.startAnimation(fadeOutAnimation)
        processing_identity.startAnimation(slideInAnimation)
        (processing_identity_loading_image.drawable as AnimationDrawable).start()

        val spannableContent = SpannableString(text)
        val start = text.indexOf(username)
        val end = start + username.length
        spannableContent.setSpan(StyleSpan(Typeface.BOLD), start, end, 0)
        processing_identity_message.text = spannableContent
    }

    private fun showConfirmationDialog() {
        val upgradeFee = if (intent.action == ACTION_FROM_INVITE) null else Coin.CENT.value
        val username = "<b>“${username.text}”</b>"
        val dialogMessage = getString(R.string.new_account_confirm_message, username)
        val dialogTitle = getString(R.string.dashpay_upgrade_fee)
        val dialog = PlatformPaymentConfirmDialog.createDialog(dialogTitle, dialogMessage, upgradeFee, invite != null)
        dialog.show(supportFragmentManager, "NewAccountConfirmDialog")
    }

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
    }

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
    }
}
