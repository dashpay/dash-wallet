/*
 * Copyright 2021 Dash Core Group.
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
package org.dash.wallet.integration.coinbase_integration.ui.dialogs

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.navigation.fragment.findNavController
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.integration.coinbase_integration.R
import org.dash.wallet.integration.coinbase_integration.databinding.DialogCoinbaseBuyDashBinding

class CoinBaseBuyDashDialog : DialogFragment() {
    private val binding by viewBinding(DialogCoinbaseBuyDashBinding::bind)
    var onCoinBaseBuyDashDialogButtonsClickListener: CoinBaseBuyDashDialogButtonsClickListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setStyle(STYLE_NO_FRAME, R.style.FullScreenDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            requestFeature(Window.FEATURE_NO_TITLE)
        }
        return inflater.inflate(R.layout.dialog_coinbase_buy_dash, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        arguments?. getInt("Type")?.let { type ->
            when (type) {
                Type.PURCHASE_ERROR.ordinal -> setPurchaseError()
                Type.TRANSFER_ERROR.ordinal -> setTransferError()
                Type.TRANSFER_SUCCESS.ordinal -> setTransferSuccess()
                Type.CONVERSION_SUCCESS.ordinal -> setConversionSuccess()
                Type.CONVERSION_ERROR.ordinal -> setConversionError()
            }

            binding.coinbaseBuyDialogPositiveButton.setOnClickListener {
                onCoinBaseBuyDashDialogButtonsClickListener?.onPositiveButtonClick(Type.values().first { it.ordinal == type })
            }
        }

        binding.coinbaseBuyDialogNegativeButton.setOnClickListener {
            dismiss()
            findNavController().popBackStack()
            findNavController().popBackStack()
        }
    }

    private fun setPurchaseError() {
        binding.coinbaseBuyDialogIcon.setImageResource(R.drawable.ic_error_red)
        binding.coinbaseBuyDialogTitle.setText(R.string.purchase_failed)
        binding.coinbaseBuyDialogTitle.setTextAppearance(R.style.Headline5_Bold_Red300)
        binding.coinbaseBuyDialogMessage.setText(R.string.purchase_failed_msg)
        binding.buyDialogContactCoinbaseSupport.isGone = true
        binding.coinbaseBuyDialogNegativeButton.isGone = true
        binding.coinbaseBuyDialogPositiveButton.setText(R.string.close)
    }
    private fun setTransferError() {
        binding.coinbaseBuyDialogIcon.setImageResource(R.drawable.ic_error_red)
        binding.coinbaseBuyDialogTitle.setText(R.string.transfer_failed)
        binding.coinbaseBuyDialogMessage.setText(R.string.transfer_failed_msg)
        binding.coinbaseBuyDialogTitle.setTextAppearance(R.style.Headline5_Bold_Red300)
        binding.buyDialogContactCoinbaseSupport.isVisible = true
        binding.coinbaseBuyDialogNegativeButton.isVisible = true
        binding.coinbaseBuyDialogNegativeButton.setText(R.string.close)
        binding.coinbaseBuyDialogPositiveButton.setText(R.string.retry)
    }
    private fun setTransferSuccess() {
        binding.coinbaseBuyDialogIcon.setImageResource(R.drawable.ic_success_green)
        binding.coinbaseBuyDialogTitle.setText(R.string.purchase_successful)
        binding.coinbaseBuyDialogTitle.setTextAppearance(R.style.Headline5_Bold_Green)
        binding.coinbaseBuyDialogMessage.setText(R.string.it_could_take_up_to_2_3_minutes)
        binding.buyDialogContactCoinbaseSupport.isGone = true
        binding.coinbaseBuyDialogNegativeButton.isGone = true
        binding.coinbaseBuyDialogPositiveButton.setText(R.string.close)
    }

    private fun setConversionError() {
        binding.coinbaseBuyDialogIcon.setImageResource(R.drawable.ic_error_red)
        binding.coinbaseBuyDialogTitle.setText(R.string.conversion_failed)
        binding.coinbaseBuyDialogMessage.setText(R.string.purchase_failed_msg)
        binding.coinbaseBuyDialogTitle.setTextAppearance(R.style.Headline5_Bold_Red300)
        binding.buyDialogContactCoinbaseSupport.isVisible = true
        binding.coinbaseBuyDialogNegativeButton.isVisible = true
        binding.coinbaseBuyDialogNegativeButton.setText(R.string.close)
        binding.coinbaseBuyDialogPositiveButton.setText(R.string.retry)
    }
    private fun setConversionSuccess() {
        binding.coinbaseBuyDialogIcon.setImageResource(R.drawable.ic_success_green)
        binding.coinbaseBuyDialogTitle.setText(R.string.conversion_successful)
        binding.coinbaseBuyDialogTitle.setTextAppearance(R.style.Headline5_Bold_Green)
        binding.coinbaseBuyDialogMessage.setText(R.string.it_could_take_up_to_5_minutes)
        binding.buyDialogContactCoinbaseSupport.isGone = true
        binding.coinbaseBuyDialogNegativeButton.isGone = true
        binding.coinbaseBuyDialogPositiveButton.setText(R.string.close)
    }
    companion object {

        fun newInstance(
            type: Type
        ): CoinBaseBuyDashDialog {
            val args = Bundle().apply {
                putInt("Type", type.ordinal)
            }
            return CoinBaseBuyDashDialog().apply {
                arguments = args
            }
        }
    }

    enum class Type {
        TRANSFER_SUCCESS,
        TRANSFER_ERROR,
        PURCHASE_ERROR,
        CONVERSION_SUCCESS,
        CONVERSION_ERROR
    }

    interface CoinBaseBuyDashDialogButtonsClickListener {
        fun onPositiveButtonClick(type: Type)
    }
}
