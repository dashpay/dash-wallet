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
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.bumptech.glide.Glide
import de.schildbach.wallet.Constants
import de.schildbach.wallet.data.DashPayProfile
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.dashpay.EditProfileViewModel
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_edit_profile.*
import kotlinx.android.synthetic.main.activity_more.dashpayUserAvatar
import kotlinx.android.synthetic.main.activity_more.userInfoContainer

class EditProfileActivity : BaseMenuActivity() {

    lateinit var editProfileViewModel: EditProfileViewModel
    lateinit var dashPayProfile: DashPayProfile

    override fun getLayoutId(): Int {
        return R.layout.activity_edit_profile
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setTitle(R.string.edit_profile)

        editProfileViewModel = ViewModelProvider(this).get(EditProfileViewModel::class.java)

        val blockchainIdentity = PlatformRepo.getInstance().getBlockchainIdentity()
        if (blockchainIdentity?.currentUsername != null) {
            userInfoContainer.visibility = View.VISIBLE
            editProfileViewModel.dashPayProfileData.observe(this, dashPayProfileObserver)
            dashpayUserAvatar.background = UserAvatarPlaceholderDrawable.getDrawable(this,
                    blockchainIdentity.currentUsername!!.toCharArray()[0])
        } else {
            finish()
        }

        val redTextColor = ContextCompat.getColor(this, R.color.dash_red)
        val mediumGrayTextColor = ContextCompat.getColor(this, R.color.medium_gray)
        display_name.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                setEditingState(true)
                displayNameCharCount.visibility = View.VISIBLE
                val charCount = s?.length ?: 0
                displayNameCharCount.text = getString(R.string.char_count, charCount,
                        Constants.DISPLAY_NAME_MAX_LENGTH)
                if (charCount > Constants.DISPLAY_NAME_MAX_LENGTH) {
                    displayNameCharCount.setTextColor(redTextColor)
                    save.isEnabled = false
                } else {
                    displayNameCharCount.setTextColor(mediumGrayTextColor)
                    save.isEnabled = true
                }
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
                val charCount = s?.length ?: 0
                aboutMeCharCount.text = getString(R.string.char_count, charCount,
                        Constants.ABOUT_ME_MAX_LENGTH)
                if (charCount > Constants.ABOUT_ME_MAX_LENGTH) {
                    aboutMeCharCount.setTextColor(redTextColor)
                    save.isEnabled = false
                } else {
                    aboutMeCharCount.setTextColor(mediumGrayTextColor)
                    save.isEnabled = true
                }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {

            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {

            }

        })

        editProfileViewModel.updateProfileRequestState.observe(this, Observer {
            if (it != null) {
                when (it.status) {
                    Status.SUCCESS -> {
                        Toast.makeText(this@EditProfileActivity, "Update successful", Toast.LENGTH_LONG).show()
                        //setEditingState(false) // if this line is here, then the Profile changes to the previous value (crazy?)
                    }
                    Status.ERROR -> {
                        var msg = it.message
                        if (msg == null) {
                            msg = "!!Error!!  ${it.exception!!.message}"
                        }
                        Toast.makeText(this@EditProfileActivity, msg, Toast.LENGTH_LONG).show()
                        //setEditingState(false)
                        save.isEnabled = true
                    }
                    Status.LOADING -> {
                        Toast.makeText(this@EditProfileActivity, "Processing update", Toast.LENGTH_LONG).show()
                    }
                    Status.CANCELED -> {
                        //setEditingState(true)
                        save.isEnabled = true
                    }
                }
            }
        })

        save.setOnClickListener {
            val updatedProfile = DashPayProfile(dashPayProfile.userId, dashPayProfile.username,
                    display_name.text.toString(), about_me.text.toString(), "",
                    dashPayProfile.createdAt, dashPayProfile.updatedAt)
            save.isEnabled = false
            editProfileViewModel.broadcastUpdateProfile(updatedProfile)
        }
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

    // This method works well when it is called the first time,
    // but when it is called after updating the profile, `it` is the old profile
    private val dashPayProfileObserver = Observer<DashPayProfile?> {
        if (it != null) {
            dashPayProfile = it
            showProfileInfo(it)
        }
    }

    private fun setEditingState(isEditing: Boolean) {
        if (!isEditing) {
            editProfileViewModel.dashPayProfileData.observe(this, dashPayProfileObserver)
        } else {
            editProfileViewModel.dashPayProfileData.removeObserver(dashPayProfileObserver)
        }
    }

}
