/*
 * Copyright 2019 Dash Core Group
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
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.view.View
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import androidx.core.content.res.ResourcesCompat
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_advanced_security.*
import java.util.concurrent.TimeUnit


enum class SecurityLevel {
    NONE, LOW, MEDIUM, HIGH, VERY_HIGH
}

class AdvancedSecurityActivity : BaseMenuActivity() {
    private val onAutoLogoutSeekBarListener = object : OnSeekBarChangeListener {
        override fun onStopTrackingTouch(seekBar: SeekBar?) { }
        override fun onStartTrackingTouch(seekBar: SeekBar?) { }
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            configuration.autoLogoutMinutes = when(auto_logout_seekbar.progress) {
                0 -> 0
                1 -> 1
                2 -> 5
                3 -> 60
                else -> TimeUnit.HOURS.toMinutes(24).toInt()
            }
            updateView()
        }
    }
    private val onBiometricLimitSeekBarChangeListener = object : OnSeekBarChangeListener {
        override fun onStopTrackingTouch(seekBar: SeekBar?) { }
        override fun onStartTrackingTouch(seekBar: SeekBar?) { }
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            configuration.biometricLimit = when(biometric_limit_seekbar.progress) {
                0 -> 0f
                1 -> 0.1f
                2 -> 0.5f
                3 -> 1f
                else -> 5f
            }
            updateView()
        }
    }
    private lateinit var dashSymbol: ImageSpan

    override fun getLayoutId(): Int {
        return R.layout.activity_advanced_security
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val drawableDash = ResourcesCompat.getDrawable(resources,
                R.drawable.ic_dash_d_black, null)
        drawableDash!!.setBounds(0, 0, 32, 32)
        dashSymbol = ImageSpan(drawableDash, ImageSpan.ALIGN_BASELINE)

        auto_logout_switch.setOnCheckedChangeListener { _, enabled ->
            configuration.autoLogoutEnabled = enabled
            updateView()
        }

        spending_confirmation_switch.setOnCheckedChangeListener { _, enabled ->
            configuration.spendingConfirmationEnabled = enabled
            updateView()
        }

        auto_logout_seekbar.setOnSeekBarChangeListener(onAutoLogoutSeekBarListener)
        biometric_limit_seekbar.setOnSeekBarChangeListener(onBiometricLimitSeekBarChangeListener)

        updateView()
        setTitle(R.string.security_title)
    }

    private fun getSecurityLevel(): SecurityLevel {
        return if (configuration.autoLogoutEnabled) {
            if (configuration.spendingConfirmationEnabled) {
                if (configuration.autoLogoutMinutes == 0) {
                    SecurityLevel.VERY_HIGH
                } else {
                    SecurityLevel.HIGH
                }
            } else {
                if (configuration.autoLogoutMinutes >= 60) {
                    SecurityLevel.LOW
                } else {
                    SecurityLevel.MEDIUM
                }
            }
        } else {
            if (configuration.spendingConfirmationEnabled) {
                SecurityLevel.MEDIUM
            } else {
                SecurityLevel.NONE
            }
        }
    }

    private fun updateView() {
        // Auto Logout group
        auto_logout_switch.isChecked = configuration.autoLogoutEnabled
        if (configuration.autoLogoutEnabled) {
            auto_logout_group.visibility = View.VISIBLE
            logout_after_time.text = getString(when(configuration.autoLogoutMinutes) {
                0 -> R.string.immediately
                1 -> R.string.one_minute
                5 -> R.string.five_minute
                60 -> R.string.one_hour
                else -> R.string.twenty_four_hours
            })
            auto_logout_seekbar.progress = when(configuration.autoLogoutMinutes) {
                0 -> 0
                1 -> 1
                5 -> 2
                60 -> 3
                else -> 4
            }
        } else {
            auto_logout_group.visibility = View.GONE
        }

        spending_confirmation_switch.isChecked = configuration.spendingConfirmationEnabled
        if (configuration.spendingConfirmationEnabled) {
            spending_confirmation_group.visibility = if (configuration.enableFingerprint) {
                View.VISIBLE
            } else {
                View.GONE
            }

            biometric_limit_value.text = configuration.biometricLimit
                    .toString().removeSuffix(".0")
            biometric_limit_seekbar.progress = when(configuration.biometricLimit) {
                0f -> 0
                .1f -> 1
                .5f -> 2
                1f -> 3
                else -> 4
            }
            if (configuration.biometricLimit == 0f) {
                spending_confirmation_hint.text = getText(R.string.spending_confirmation_hint_zero)
            } else {
                val builder = SpannableStringBuilder()
                builder.append(getText(R.string.spending_confirmation_hint_above_zero)).append("  ")
                builder.setSpan(dashSymbol, builder.length - 1, builder.length, 0)
                builder.append(" ").append(biometric_limit_value.text)
                spending_confirmation_hint.text = builder
            }
        } else {
            spending_confirmation_group.visibility = View.GONE
        }

        // Security Level
        val securityLevel = getSecurityLevel()
        security_level.text = getString(when(securityLevel) {
            SecurityLevel.NONE -> R.string.security_none
            SecurityLevel.LOW -> R.string.security_low
            SecurityLevel.MEDIUM -> R.string.security_medium
            SecurityLevel.HIGH -> R.string.security_high
            else -> R.string.security_very_high
        })
        security_level.setTextColor(ResourcesCompat.getColor(resources, when(securityLevel) {
            SecurityLevel.NONE -> R.color.dash_red
            SecurityLevel.LOW -> R.color.dash_orange
            SecurityLevel.MEDIUM -> R.color.dash_orange
            SecurityLevel.HIGH -> R.color.dash_blue
            else -> R.color.dash_green
        }, null))
        security_icon.setImageResource(when(securityLevel) {
            SecurityLevel.NONE -> R.drawable.security_filled_red
            SecurityLevel.LOW -> R.drawable.security_filled_orange
            SecurityLevel.MEDIUM -> R.drawable.security_filled_orange
            SecurityLevel.HIGH -> R.drawable.security_filled_blue
            else -> R.drawable.security_filled_green
        })
    }

    fun resetToDefault(view: View) {
        configuration.autoLogoutEnabled = true
        configuration.autoLogoutMinutes = 1
        configuration.spendingConfirmationEnabled = true
        configuration.biometricLimit = .5f
        updateView()
    }
}
