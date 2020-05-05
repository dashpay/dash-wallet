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
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProviders
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.IdentityCreationState
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.dashpay.CreateIdentityService.Companion.createIntent
import de.schildbach.wallet.ui.dashpay.DashPayViewModel
import de.schildbach.wallet.ui.dashpay.NewAccountConfirmDialog
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.create_username.*
import kotlinx.android.synthetic.main.users_orbit.*
import org.bitcoinj.core.Coin
import org.dash.wallet.common.InteractionAwareActivity
import org.slf4j.LoggerFactory

class CreateUsernameActivity : InteractionAwareActivity(), TextWatcher {

    private val regularTypeFace by lazy { ResourcesCompat.getFont(this, R.font.montserrat_regular) }
    private val mediumTypeFace by lazy { ResourcesCompat.getFont(this, R.font.montserrat_medium) }
    private val slideInAnimation by lazy { AnimationUtils.loadAnimation(this, R.anim.slide_in_bottom) }
    private val fadeOutAnimation by lazy { AnimationUtils.loadAnimation(this, R.anim.fade_out) }
    private lateinit var completeUsername: String
    private lateinit var dashPayViewModel: DashPayViewModel
    //    private lateinit var securityGuard: SecurityGuard
    private lateinit var walletApplication: WalletApplication


    private var handler: Handler = Handler()
    private lateinit var checkUsernameNotExistRunnable: Runnable

    companion object {
        @JvmStatic
        public val COMPLETE_USERNAME = "complete_username"
        private val log = LoggerFactory.getLogger(CreateUsernameActivity::class.java)
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.create_username)

        choose_username_title.text = getText(R.string.choose_your_username)
        close_btn.setOnClickListener { finish() }
        username.addTextChangedListener(this)
        register_btn.setOnClickListener { showConfirmationDialog() }
        processing_identity_dismiss_btn.setOnClickListener { finish() }

        val intentUsername = intent?.extras?.getString(COMPLETE_USERNAME)
        if (intentUsername != null) {
            this.completeUsername = intentUsername
            showCompleteState()
        }

        walletApplication = application as WalletApplication

        val confirmTransactionSharedViewModel = ViewModelProviders.of(this)
                .get(SingleActionSharedViewModel::class.java)
        confirmTransactionSharedViewModel.clickConfirmButtonEvent.observe(this, Observer {
            // setup the wallet
            val wallet = (application as WalletApplication).wallet

            //get key parameter
            val username = username.text.toString()
            if (wallet.isEncrypted) {
                ContextCompat.startForegroundService(this, createIntent(this, username))

                // finish this activity on error or when registration is complete
                AppDatabase.getAppDatabase().identityCreationStateDao().load().observe(this, Observer {
                    if (it != null && it.error) {
                        finish()
                    } else when (it?.state) {
                        IdentityCreationState.State.USERNAME_REGISTERED -> {
                            finish()
                        }
                    }
                })
            } else {
                dashPayViewModel.createUsername(username, wallet.keyChainSeed, null)
            }
            showProcessingState()
        })

        dashPayViewModel = ViewModelProvider(this).get(DashPayViewModel::class.java)

        dashPayViewModel.getUsernameLiveData.observe(this, Observer {
            username_exists_req.visibility = View.VISIBLE
            username_exists_req_label.visibility = View.VISIBLE
            when (it.status) {
                Status.LOADING -> {
                    // this is delayed therefore the UI state is configured before calling checkUsernameNotExist(...)
                }
                Status.ERROR -> {
                    username_exists_req_progress.visibility = View.INVISIBLE
                    username_exists_req_img.visibility = View.VISIBLE
                    username_exists_req_img.setImageResource(R.drawable.ic_username_requirement_x)
                    username_exists_req_label.typeface = mediumTypeFace
                    username_exists_req_label.setTextColor(ResourcesCompat.getColor(resources, R.color.dash_red, null))
                    username_exists_req_label.setText(R.string.platform_communication_error)
                    register_btn.isEnabled = false
                }
                Status.SUCCESS -> {
                    if (it.data != null) {
                        // This user name exists
                        username_exists_req_progress.visibility = View.INVISIBLE
                        username_exists_req_img.visibility = View.VISIBLE
                        username_exists_req_img.setImageResource(R.drawable.ic_username_requirement_x)
                        username_exists_req_label.typeface = mediumTypeFace
                        username_exists_req_label.setTextColor(ResourcesCompat.getColor(resources, R.color.dash_red, null))
                        username_exists_req_label.setText(R.string.identity_username_taken)
                        register_btn.isEnabled = false
                    } else {
                        username_exists_req_progress.visibility = View.INVISIBLE
                        username_exists_req_img.visibility = View.VISIBLE
                        username_exists_req_img.setImageResource(R.drawable.ic_username_requirement_checkmark)
                        username_exists_req_label.typeface = mediumTypeFace
                        username_exists_req_label.setTextColor(ResourcesCompat.getColor(resources, R.color.dark_text, null))
                        username_exists_req_label.setText(R.string.identity__username_available)
                        register_btn.isEnabled = true
                    }
                }
            }
        })

//        dashPayViewModel.createUsernameLiveData.observe(this, Observer {
//            when(it.status) {
//                Status.LOADING -> {
//
//                }
//                Status.SUCCESS -> {
//
//                }
//                Status.ERROR -> {
//
//                }
//            }
//        })

//        try {
//            securityGuard = SecurityGuard()
//        } catch (e: Exception) {
//            log.error("Unable to instantiate SecurityGuard", e)
//            finish()
//            return
//        }
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
        val isValid: Boolean

        min_chars_req_img.visibility = if (uname.length in 3..23) {
            isValid = true
            min_chars_req_label.typeface = mediumTypeFace
            View.VISIBLE
        } else {
            isValid = false
            min_chars_req_label.typeface = regularTypeFace
            View.INVISIBLE
        }

        return isValid
    }

    private fun validateUsernameCharacters(uname: String): Boolean {
        val isValid: Boolean
        val alphaNumValid = !Regex("[^a-z0-9]").containsMatchIn(uname)

        alphanum_req_img.visibility = if (uname.isNotEmpty() && alphaNumValid) {
            isValid = true
            alphanum_req_label.typeface = mediumTypeFace
            View.VISIBLE
        } else {
            isValid = false
            alphanum_req_label.typeface = regularTypeFace
            View.INVISIBLE
        }

        return isValid
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
            val usernameIsValid = validateUsernameCharacters(username) && validateUsernameSize(username)

            if (usernameIsValid) {
                register_btn.isEnabled = true
                username_exists_req_label.visibility = View.GONE
                username_exists_req_img.visibility = View.GONE
            }

            if (usernameIsValid) {//ensure username meets basic rules before making a Platform query
                username_exists_req_progress.visibility = View.VISIBLE
                username_exists_req_img.visibility = View.INVISIBLE
                username_exists_req_label.visibility = View.VISIBLE
                username_exists_req_label.typeface = regularTypeFace
                username_exists_req_label.setTextColor(ResourcesCompat.getColor(resources, R.color.dark_text, null))
                username_exists_req_label.setText(R.string.identity_username_validating)
                register_btn.isEnabled = false
                checkUsernameNotExist(username)
            } else {
                username_exists_req_label.visibility = View.GONE
                username_exists_req_img.visibility = View.GONE
            }
        }
    }

    private fun showProcessingState() {
        val username = username.text.toString()
        val text = getString(R.string.username_being_created, username)

        processing_identity.visibility = View.VISIBLE
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
        val username = username.text.toString()
        val upgradeFee = Coin.CENT
        val dialog = NewAccountConfirmDialog.createDialog(upgradeFee.value, username)
        dialog.show(supportFragmentManager, "NewAccountConfirmDialog")

    }

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
    }

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
    }

//    private fun handleDecryptPIN(password: String) {
//        if (walletApplication.wallet.isEncrypted()) {
//            object : DeriveKeyTask(handler, walletApplication.scryptIterationsTarget()) {
//                override fun onSuccess(encryptionKey: KeyParameter, wasChanged: Boolean) {
//                    handleDecryptSeed(encryptionKey, password)
//                }
//            }.deriveKey(walletApplication.wallet, password)
//        } else {
//            walletApplication.wallet.initializeAuthenticationKeyChains(walletApplication.wallet.keyChainSeed, null)
//        }
//    }
//
//    private fun handleDecryptSeed(encryptionKey: KeyParameter, password: String) {
//        val wallet: Wallet = walletApplication.wallet
//        val username = username.text.toString()
//        if (wallet.isEncrypted) {
//            object : DecryptSeedTask(handler) {
//                override fun onSuccess(seed: DeterministicSeed) {
//                    dashPayViewModel.createUsername(username, seed, encryptionKey)
//                }
//
//                override fun onBadPassphrase() { // can this happen?
//                }
//            }.decryptSeed(wallet.activeKeyChain.seed, wallet.keyCrypter, encryptionKey)
//        } else {
//            dashPayViewModel.createUsername(username, wallet.keyChainSeed, null)
//        }
//    }

}