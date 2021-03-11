/*
 * Copyright 2021 Dash Core Group
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

package de.schildbach.wallet.ui.invite

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.ui.CreateUsernameActivity
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_accept_invite.*

class AcceptInviteActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_INVITE = "extra_invite"

        fun createIntent(context: Context, invite: InvitationLinkData): Intent {
            return Intent(context, AcceptInviteActivity::class.java).apply {
                putExtra(EXTRA_INVITE, invite)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_accept_invite)

        viewpager.adapter = WelcomePagerAdapter(supportFragmentManager, 0)
        page_indicator.setViewPager(viewpager)

        continue_button.setOnClickListener {
            if (viewpager.currentItem < 2) {
                viewpager.setCurrentItem(viewpager.currentItem + 1, true)
            } else {
                val invite = intent.getParcelableExtra<InvitationLinkData>(EXTRA_INVITE)
                startActivity(CreateUsernameActivity.createIntentFromInvite(this, invite))
                finish()
            }
        }
    }

    override fun finish() {
        setResult(Activity.RESULT_OK)
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private class WelcomePagerAdapter(fragmentManager: FragmentManager, behavior: Int)
        : FragmentPagerAdapter(fragmentManager, behavior) {

        override fun getItem(position: Int): Fragment {
            when (position) {
                0 -> return AcceptInviteFragment.newInstance(R.string.invitation_accept_title_1, R.string.invitation_accept_message_1,
                        R.drawable.ic_accept_invite_slide_1)
                1 -> return AcceptInviteFragment.newInstance(R.string.invitation_accept_title_2, R.string.invitation_accept_message_2,
                        R.drawable.ic_accept_invite_slide_2)
                2 -> return AcceptInviteFragment.newInstance(R.string.invitation_accept_title_3, R.string.invitation_accept_message_3,
                        R.drawable.ic_accept_invite_slide_3)
            }
            return Fragment()
        }

        override fun getCount(): Int {
            return 3
        }
    }

}
