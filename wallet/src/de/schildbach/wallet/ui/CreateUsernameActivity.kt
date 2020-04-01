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
import android.text.Editable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.StyleSpan
import android.view.View
import android.view.animation.AnimationUtils
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.data.IdentityCreationState
import de.schildbach.wallet.ui.dashpay.NewAccountConfirmDialog
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.create_username.*
import org.dash.wallet.common.InteractionAwareActivity
import java.util.concurrent.Executors

class CreateUsernameActivity : InteractionAwareActivity(), TextWatcher {

    private val regularTypeFace by lazy { ResourcesCompat.getFont(this, R.font.montserrat_regular) }
    private val mediumTypeFace by lazy { ResourcesCompat.getFont(this, R.font.montserrat_medium) }
    private val slideInAnimation by lazy { AnimationUtils.loadAnimation(this, R.anim.slide_in_bottom) }
    private val fadeOutAnimation by lazy { AnimationUtils.loadAnimation(this, R.anim.fade_out) }
    private var completeUsername: String? = null

    companion object {
        @JvmStatic
        public val COMPLETE_USERNAME = "complete_username"
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
    }

    private fun showCompleteState() {
        registration_content.visibility = View.GONE
        processing_identity.visibility = View.GONE
        choose_username_title.visibility = View.GONE
        identity_complete.visibility = View.VISIBLE

        val text = getString(R.string.identity_complete_message, completeUsername!!)

        val spannableContent = SpannableString(text)
        val start = text.indexOf(completeUsername!!)
        val end = start + completeUsername!!.length
        spannableContent.setSpan(StyleSpan(Typeface.BOLD), start, end, 0)
        identity_complete_text.text = spannableContent
        identity_complete_button.setOnClickListener { finish() }
    }

    private fun validateUsernameSize(uname: String): Boolean {
        val isValid: Boolean

        min_chars_req_img.visibility = if (uname.length in 4..23) {
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

    override fun afterTextChanged(s: Editable?) {
        val username = s?.toString()

        if (username != null) {
            val usernameIsValid = validateUsernameCharacters(username) && validateUsernameSize(username)
            register_btn.isEnabled = usernameIsValid
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

        Executors.newSingleThreadExecutor().execute {
            val identityCreationState = IdentityCreationState(IdentityCreationState
                    .State.PROCESSING_PAYMENT, username)
            AppDatabase.getAppDatabase().identityCreationStateDao().insert(identityCreationState)
        }
    }

    private fun showConfirmationDialog() {
        val dialog = NewAccountConfirmDialog.createDialog()
        dialog.show(supportFragmentManager, "NewAccountConfirmDialog")

        val confirmTransactionSharedViewModel = ViewModelProviders.of(this)
                .get(SingleActionSharedViewModel::class.java)
        confirmTransactionSharedViewModel.clickConfirmButtonEvent.observe(this, Observer {
            showProcessingState()
        })
    }

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
    }

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
    }

}