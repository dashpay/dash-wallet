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
import android.graphics.Typeface
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_accept_invite.continue_button
import kotlinx.android.synthetic.main.activity_onboard_from_invite.*

class OnboardFromInviteActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_MODE = "extra_mode"
        private const val EXTRA_GO_NEXT = "extra_go_next"

        fun createIntent(context: Context, mode: Mode, goNextIntent: Intent): Intent {
            return Intent(context, OnboardFromInviteActivity::class.java).apply {
                putExtra(EXTRA_MODE, mode.ordinal)
                putExtra(EXTRA_GO_NEXT, goNextIntent)
            }
        }
    }

    enum class Mode {
        STEP_1,
        STEP_2,
        STEP_3
    }

    private val goNextIntent by lazy {
        intent.getParcelableExtra<Intent>(EXTRA_GO_NEXT)
    }

    private var mode = Mode.STEP_1
        set(value) {
            field = value
            when (value) {
                Mode.STEP_1 -> {
                    activate(title1, true)
                    activate(title2, false)
                    activate(title3, false)
                    step1.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
                    step2.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
                    step3.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
                    icon2.setImageResource(R.drawable.ic_onboard_from_invite_2_disabled)
                    icon3.setImageResource(R.drawable.ic_onboard_from_invite_3_disabled)
                }
                Mode.STEP_2 -> {
                    activate(title1, true)
                    activate(title2, true)
                    activate(title3, false)
                    step1.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_onboard_from_invite_check, 0)
                    step2.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
                    step3.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
                    icon2.setImageResource(R.drawable.ic_onboard_from_invite_2)
                    icon3.setImageResource(R.drawable.ic_onboard_from_invite_3_disabled)
                }
                Mode.STEP_3 -> {
                    activate(title1, true)
                    activate(title2, true)
                    activate(title3, true)
                    step1.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_onboard_from_invite_check, 0)
                    step2.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_onboard_from_invite_check, 0)
                    step3.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
                    icon2.setImageResource(R.drawable.ic_onboard_from_invite_2)
                    icon3.setImageResource(R.drawable.ic_onboard_from_invite_3)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboard_from_invite)

        continue_button.setOnClickListener {
            startActivity(goNextIntent)
        }
        mode = Mode.values()[intent.getIntExtra(EXTRA_MODE, 0)]
    }

    private fun activate(view: TextView, active: Boolean) {
        view.run {
            if (active) {
                setTextColor(ContextCompat.getColor(this@OnboardFromInviteActivity, R.color.dash_black))
                setTypeface(null, Typeface.BOLD)
            } else {
                setTextColor(ContextCompat.getColor(this@OnboardFromInviteActivity, R.color.dash_gray))
                setTypeface(null, Typeface.NORMAL)
            }
        }
    }

    override fun finish() {
        setResult(Activity.RESULT_OK)
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}