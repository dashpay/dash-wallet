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

package de.schildbach.wallet.ui.more

import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.view.View
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import androidx.core.content.res.ResourcesCompat
import androidx.core.os.bundleOf
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.LockScreenActivity
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.ActivityAdvancedSecurityBinding
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import java.util.concurrent.TimeUnit
import javax.inject.Inject

enum class SecurityLevel {
    NONE, LOW, MEDIUM, HIGH, VERY_HIGH
}

@AndroidEntryPoint
class AdvancedSecurityActivity : LockScreenActivity() {
    @Inject
    lateinit var analytics: AnalyticsService
    private lateinit var binding: ActivityAdvancedSecurityBinding

    private val onAutoLogoutSeekBarListener = object : OnSeekBarChangeListener {
        override fun onStopTrackingTouch(seekBar: SeekBar?) {
            val value = autoLogoutProgressToTimeValue(binding.autoLogoutSeekbar.progress)
            analytics.logEvent(
                AnalyticsConstants.Security.AUTO_LOGOUT_TIMER_VALUE,
                bundleOf("timer_value" to value)
            )
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) { }
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            configuration.autoLogoutMinutes = autoLogoutProgressToTimeValue(binding.autoLogoutSeekbar.progress)
            updateView()
        }
    }

    private val onBiometricLimitSeekBarChangeListener = object : OnSeekBarChangeListener {
        override fun onStopTrackingTouch(seekBar: SeekBar?) {
            val value = biometricProgressToLimitValue(binding.biometricLimitSeekbar.progress)
            analytics.logEvent(
                AnalyticsConstants.Security.SPENDING_CONFIRMATION_LIMIT,
                bundleOf("limit_value" to value)
            )
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) { }
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            configuration.biometricLimit = biometricProgressToLimitValue(binding.biometricLimitSeekbar.progress)
            updateView()
        }
    }
    private lateinit var dashSymbol: ImageSpan

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityAdvancedSecurityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.appBar.toolbar.title = getString(R.string.security_title)
        binding.appBar.toolbar.setNavigationOnClickListener { finish() }

        val drawableDash = ResourcesCompat.getDrawable(
            resources,
            R.drawable.ic_dash_d_black,
            null
        )
        drawableDash!!.setBounds(0, 0, 32, 32)
        dashSymbol = ImageSpan(drawableDash, ImageSpan.ALIGN_BASELINE)

        binding.autoLogoutSwitch.setOnCheckedChangeListener { _, enabled ->
            if (configuration.autoLogoutEnabled != enabled) {
                analytics.logEvent(
                    if (enabled) {
                        AnalyticsConstants.Security.AUTO_LOGOUT_ON
                    } else {
                        AnalyticsConstants.Security.AUTO_LOGOUT_OFF
                    },
                    bundleOf()
                )
            }
            configuration.autoLogoutEnabled = enabled
            updateView()
        }

        binding.spendingConfirmationSwitch.setOnCheckedChangeListener { _, enabled ->
            if (configuration.spendingConfirmationEnabled != enabled) {
                analytics.logEvent(
                    if (enabled) {
                        AnalyticsConstants.Security.SPENDING_CONFIRMATION_ON
                    } else {
                        AnalyticsConstants.Security.SPENDING_CONFIRMATION_OFF
                    },
                    bundleOf()
                )
            }

            configuration.spendingConfirmationEnabled = enabled
            updateView()
        }

        binding.autoLogoutSeekbar.setOnSeekBarChangeListener(onAutoLogoutSeekBarListener)
        binding.biometricLimitSeekbar.setOnSeekBarChangeListener(onBiometricLimitSeekBarChangeListener)
        binding.resetToDefaultBtn.setOnClickListener { resetToDefault() }

        updateView()
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
        binding.autoLogoutSwitch.isChecked = configuration.autoLogoutEnabled
        if (configuration.autoLogoutEnabled) {
            binding.autoLogoutGroup.visibility = View.VISIBLE
            binding.logoutAfterTime.text = getString(
                when (configuration.autoLogoutMinutes) {
                    0 -> R.string.immediately
                    1 -> R.string.one_minute
                    5 -> R.string.five_minute
                    60 -> R.string.one_hour
                    else -> R.string.twenty_four_hours
                }
            )
            binding.autoLogoutSeekbar.progress = when (configuration.autoLogoutMinutes) {
                0 -> 0
                1 -> 1
                5 -> 2
                60 -> 3
                else -> 4
            }
        } else {
            binding.autoLogoutGroup.visibility = View.GONE
        }

        binding.spendingConfirmationSwitch.isChecked = configuration.spendingConfirmationEnabled
        if (configuration.spendingConfirmationEnabled) {
            binding.spendingConfirmationGroup.visibility = if (configuration.enableFingerprint) {
                View.VISIBLE
            } else {
                View.GONE
            }

            binding.biometricLimitValue.text = configuration.biometricLimit
                .toString().removeSuffix(".0")
            binding.biometricLimitSeekbar.progress = when (configuration.biometricLimit) {
                0f -> 0
                .1f -> 1
                .5f -> 2
                1f -> 3
                else -> 4
            }

            if (configuration.biometricLimit == 0f) {
                binding.spendingConfirmationHint.text = getText(R.string.spending_confirmation_hint_zero)
            } else {
                val builder = SpannableStringBuilder()
                builder.append(getText(R.string.spending_confirmation_hint_above_zero)).append("  ")
                builder.setSpan(dashSymbol, builder.length - 1, builder.length, 0)
                builder.append(" ").append(binding.biometricLimitValue.text)
                binding.spendingConfirmationHint.text = builder
            }
        } else {
            binding.spendingConfirmationGroup.visibility = View.GONE
        }

        // Security Level
        val securityLevel = getSecurityLevel()
        binding.securityLevel.text = getString(
            when (securityLevel) {
                SecurityLevel.NONE -> R.string.security_none
                SecurityLevel.LOW -> R.string.security_low
                SecurityLevel.MEDIUM -> R.string.security_medium
                SecurityLevel.HIGH -> R.string.security_high
                else -> R.string.security_very_high
            }
        )
        binding.securityLevel.setTextColor(
            ResourcesCompat.getColor(
                resources,
                when (securityLevel) {
                    SecurityLevel.NONE -> R.color.dash_red
                    SecurityLevel.LOW -> R.color.dash_orange
                    SecurityLevel.MEDIUM -> R.color.dash_orange
                    SecurityLevel.HIGH -> R.color.dash_blue
                    else -> R.color.dash_green
                },
                null
            )
        )
        binding.securityIcon.setImageResource(
            when (securityLevel) {
                SecurityLevel.NONE -> R.drawable.security_filled_red
                SecurityLevel.LOW -> R.drawable.security_filled_orange
                SecurityLevel.MEDIUM -> R.drawable.security_filled_orange
                SecurityLevel.HIGH -> R.drawable.security_filled_blue
                else -> R.drawable.security_filled_green
            }
        )
    }

    private fun resetToDefault() {
        configuration.autoLogoutEnabled = true
        configuration.autoLogoutMinutes = 1
        configuration.spendingConfirmationEnabled = true
        configuration.biometricLimit = .5f
        updateView()
        analytics.logEvent(AnalyticsConstants.Security.RESET_TO_DEFAULT, bundleOf())
    }

    private fun autoLogoutProgressToTimeValue(progress: Int): Int {
        return when (progress) {
            0 -> 0
            1 -> 1
            2 -> 5
            3 -> 60
            else -> TimeUnit.HOURS.toMinutes(24).toInt()
        }
    }

    private fun biometricProgressToLimitValue(progress: Int): Float {
        return when (progress) {
            0 -> 0f
            1 -> 0.1f
            2 -> 0.5f
            3 -> 1f
            else -> 5f
        }
    }

    override fun onLockScreenActivated() {
        super.onLockScreenActivated()
        finish()
    }
}
