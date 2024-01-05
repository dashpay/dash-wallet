/*
 * Copyright 2022 Dash Core Group.
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

package org.dash.wallet.integrations.crowdnode.ui.dialogs

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.integrations.crowdnode.R
import org.dash.wallet.integrations.crowdnode.databinding.DialogWithdrawalLimitsBinding
import org.dash.wallet.integrations.crowdnode.model.WithdrawalLimitPeriod
import java.lang.IllegalArgumentException

class WithdrawalLimitsInfoDialog(
    private val limitPerTx: Coin,
    private val limitPerHour: Coin,
    private val limitPerDay: Coin,
    private val highlightedLimit: WithdrawalLimitPeriod? = null,
    private val okButtonText: String? = null
) : AdaptiveDialog(R.layout.dialog_withdrawal_limits) {
    private val limitFormat = MonetaryFormat.BTC.minDecimals(0).optionalDecimals(0).noCode()
    private val binding by viewBinding(DialogWithdrawalLimitsBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        arguments = if (okButtonText.isNullOrEmpty()) {
            bundleOf(
                NEG_BUTTON_ARG to getString(android.R.string.ok)
            )
        } else {
            binding.withdrawOnlineText.isVisible = true
            bundleOf(
                NEG_BUTTON_ARG to getString(R.string.button_close),
                POS_BUTTON_ARG to okButtonText
            )
        }

        binding.perTransactionLimit.text = limitFormat.format(limitPerTx)
        binding.perHourLimit.text = limitFormat.format(limitPerHour)
        binding.perDayLimit.text = limitFormat.format(limitPerDay)

        highlightedLimit?.let {
            val warningColor = resources.getColor(R.color.system_red, null)
            val colorStateLit = ColorStateList.valueOf(warningColor)
            binding.icon.setImageResource(R.drawable.ic_error)

            when (highlightedLimit) {
                WithdrawalLimitPeriod.PerTransaction -> {
                    binding.perTransactionLabel.setTextColor(warningColor)
                    binding.perTransactionLimit.setTextColor(warningColor)
                    TextViewCompat.setCompoundDrawableTintList(binding.perTransactionLimit, colorStateLit)
                }
                WithdrawalLimitPeriod.PerHour -> {
                    binding.perHourLabel.setTextColor(warningColor)
                    binding.perHourLimit.setTextColor(warningColor)
                    TextViewCompat.setCompoundDrawableTintList(binding.perHourLimit, colorStateLit)
                }
                WithdrawalLimitPeriod.PerDay -> {
                    binding.perDayLabel.setTextColor(warningColor)
                    binding.perDayLimit.setTextColor(warningColor)
                    TextViewCompat.setCompoundDrawableTintList(binding.perDayLimit, colorStateLit)
                }
                else -> {
                    throw IllegalArgumentException("highlightedLimit $highlightedLimit not supported")
                }
            }
        }

        super.onViewCreated(view, savedInstanceState)
    }
}
