/*
 * Copyright 2020 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.bumptech.glide.Glide
import de.schildbach.wallet.Constants
import de.schildbach.wallet.data.DashPayProfile
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.dashpay.EditProfileViewModel
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_edit_profile.*

class EditProfileActivity : BaseMenuActivity() {

    private lateinit var editProfileViewModel: EditProfileViewModel
    private var isEditing: Boolean = false

    override fun getLayoutId(): Int {
        return R.layout.activity_edit_profile
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)

        setTitle(R.string.edit_profile)

        initViewModel()

        display_name.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            display_name_char_count.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
        }
        val redTextColor = ContextCompat.getColor(this, R.color.dash_red)
        val mediumGrayTextColor = ContextCompat.getColor(this, R.color.medium_gray)
        display_name.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                setEditingState(true)
                val charCount = s?.trim()?.length ?: 0
                display_name_char_count.text = getString(R.string.char_count, charCount,
                        Constants.DISPLAY_NAME_MAX_LENGTH)
                if (charCount > Constants.DISPLAY_NAME_MAX_LENGTH) {
                    display_name_char_count.setTextColor(redTextColor)
                } else {
                    display_name_char_count.setTextColor(mediumGrayTextColor)
                }
                activateDeactivateSave()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {

            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {

            }
        })

        about_me.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                setEditingState(true)
                aboutMeCharCount.visibility = View.VISIBLE
                val charCount = s?.trim()?.length ?: 0
                aboutMeCharCount.text = getString(R.string.char_count, charCount,
                        Constants.ABOUT_ME_MAX_LENGTH)
                if (charCount > Constants.ABOUT_ME_MAX_LENGTH) {
                    aboutMeCharCount.setTextColor(redTextColor)
                } else {
                    aboutMeCharCount.setTextColor(mediumGrayTextColor)
                }
                activateDeactivateSave()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {

            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {

            }

        })
        save.setOnClickListener {
            save()
        }
    }

    private fun initViewModel() {
        editProfileViewModel = ViewModelProvider(this).get(EditProfileViewModel::class.java)

        // first ensure that we have a registered username
        editProfileViewModel.dashPayProfileData.observe(this, Observer { dashPayProfile ->
            if (dashPayProfile != null) {
                showProfileInfo(dashPayProfile)
            } else {
                finish()
            }
        })
        editProfileViewModel.updateProfileRequestState.observe(this, Observer {
            when (it.status) {
                Status.LOADING -> {
                    if (it.progress == 100) {
                        Toast.makeText(this@EditProfileActivity, "Update successful", Toast.LENGTH_LONG).show()
                    }
                }
                Status.ERROR -> {
                    var msg = it.message
                    if (msg == null) {
                        msg = "!!Error!!  ${it.exception!!.message}"
                    }
                    Toast.makeText(this@EditProfileActivity, msg, Toast.LENGTH_LONG).show()
                    setEditingState(true)
                }
                else -> {
                    // ignore
                }
            }
            setEditingState(it.status != Status.SUCCESS)
            activateDeactivateSave()
        })
    }

    fun activateDeactivateSave() {
        save.isEnabled = !(display_name.text.length > Constants.DISPLAY_NAME_MAX_LENGTH || about_me.text.length > Constants.ABOUT_ME_MAX_LENGTH)
    }

    fun save() {
        val displayName = display_name.text.toString().trim()
        val publicMessage = about_me.text.toString().trim()
        editProfileViewModel.broadcastUpdateProfile(displayName, publicMessage)
        save.isEnabled = false
        finish()
    }

    private fun showProfileInfo(profile: DashPayProfile) {
        val defaultAvatar = UserAvatarPlaceholderDrawable.getDrawable(this,
                profile.username.toCharArray()[0])
        if (profile.avatarUrl.isNotEmpty()) {
            Glide.with(dashpayUserAvatar).load(profile.avatarUrl).circleCrop()
                    .placeholder(defaultAvatar).into(dashpayUserAvatar)
        } else {
            dashpayUserAvatar.setImageDrawable(defaultAvatar)
        }

        about_me.setText(profile.publicMessage)
        display_name.setText(profile.displayName)
    }

    private fun setEditingState(isEditing: Boolean) {
        this.isEditing = isEditing
    }

}
