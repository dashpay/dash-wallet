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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import com.bumptech.glide.Glide
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.Constants
import de.schildbach.wallet.data.DashPayProfile
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_edit_profile.*
import kotlinx.android.synthetic.main.activity_more.dashpayUserAvatar
import kotlinx.android.synthetic.main.activity_more.userInfoContainer

class EditProfileActivity : BaseMenuActivity() {

    override fun getLayoutId(): Int {
        return R.layout.activity_edit_profile
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setTitle(R.string.edit_profile)

        val blockchainIdentity = PlatformRepo.getInstance().getBlockchainIdentity()
        if (blockchainIdentity?.currentUsername != null) {
            userInfoContainer.visibility = View.VISIBLE
            AppDatabase.getAppDatabase().dashPayProfileDaoAsync().loadByUserIdDistinct(blockchainIdentity.uniqueIdString)
                    .observe(this, Observer {
                        if (it != null) {
                            showProfileInfo(it)
                        }
                    })
            dashpayUserAvatar.background = UserAvatarPlaceholderDrawable.getDrawable(this,
                    blockchainIdentity.currentUsername!!.toCharArray()[0])
        } else {
            finish()
        }

        val redTextColor = ContextCompat.getColor(this, R.color.dash_red)
        val mediumGrayTextColor = ContextCompat.getColor(this, R.color.medium_gray)
        displayName.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                displayNameCharCount.visibility = View.VISIBLE
                val charCount = s?.length ?: 0
                displayNameCharCount.text = getString(R.string.char_count, charCount,
                        Constants.DISPLAY_NAME_MAX_LENGTH)
                if (charCount > Constants.DISPLAY_NAME_MAX_LENGTH) {
                    displayNameCharCount.setTextColor(redTextColor)
                } else {
                    displayNameCharCount.setTextColor(mediumGrayTextColor)
                }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {

            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {

            }
        })

        aboutMe.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                aboutMeCharCount.visibility = View.VISIBLE
                val charCount = s?.length ?: 0
                aboutMeCharCount.text = getString(R.string.char_count, charCount,
                        Constants.ABOUT_ME_MAX_LENGTH)
                if (charCount > Constants.ABOUT_ME_MAX_LENGTH) {
                    aboutMeCharCount.setTextColor(redTextColor)
                } else {
                    aboutMeCharCount.setTextColor(mediumGrayTextColor)
                }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {

            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {

            }

        })
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

        aboutMe.setText(profile.publicMessage)
        displayName.setText(profile.displayName)
    }

}
