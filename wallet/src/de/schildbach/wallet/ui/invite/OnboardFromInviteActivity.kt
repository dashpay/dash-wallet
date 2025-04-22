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

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.ActivityOnboardFromInviteBinding
import org.dash.wallet.common.data.OnboardingState

@Deprecated("DO NOT USE")
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

    private lateinit var binding: ActivityOnboardFromInviteBinding

    private val goNextIntent by lazy {
        intent.getParcelableExtra<Intent>(EXTRA_GO_NEXT)
    }

    private var mode = Mode.STEP_1
        set(value) {
            field = value
            when (value) {
                Mode.STEP_1 -> {
                    binding.apply {
                        activate(title1, true)
                        activate(title2, false)
                        activate(title3, false)
                        step1.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
                        step2.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
                        step3.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
                        icon2.setImageResource(R.drawable.ic_onboard_from_invite_2_disabled)
                        icon3.setImageResource(R.drawable.ic_onboard_from_invite_3_disabled)
                    }
                }
                Mode.STEP_2 -> {
                    binding.apply {
                        activate(title1, true)
                        activate(title2, true)
                        activate(title3, false)
                        step1.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_check_circle_green, 0)
                        step2.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
                        step3.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
                        icon2.setImageResource(R.drawable.ic_onboard_from_invite_2)
                        icon3.setImageResource(R.drawable.ic_onboard_from_invite_3_disabled)
                    }
                }
                Mode.STEP_3 -> {
                    binding.apply {
                        activate(title1, true)
                        activate(title2, true)
                        activate(title3, true)
                        step1.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_check_circle_green, 0)
                        step2.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_check_circle_green, 0)
                        step3.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
                        icon2.setImageResource(R.drawable.ic_onboard_from_invite_2)
                        icon3.setImageResource(R.drawable.ic_onboard_from_invite_3)
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardFromInviteBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.continueButton.setOnClickListener {
            startActivity(goNextIntent)
            if (mode == Mode.STEP_3) {
                // the wallet has been created, there is no going back
                finish()
            }
        }
        mode = Mode.entries.toTypedArray()[intent.getIntExtra(EXTRA_MODE, 0)]
        OnboardingState.add()
    }

    override fun onDestroy() {
        super.onDestroy()
        OnboardingState.remove()
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
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (mode == Mode.STEP_3) {
            startActivity(goNextIntent)
            finish()
        } else {
            super.onBackPressed()
        }
    }
}