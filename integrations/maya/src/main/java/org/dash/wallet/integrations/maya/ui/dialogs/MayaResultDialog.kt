/*
 * Copyright 2024 Dash Core Group.
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
package org.dash.wallet.integrations.maya.ui.dialogs

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Toast
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.Constants
import org.dash.wallet.integrations.maya.R
import org.dash.wallet.integrations.maya.databinding.DialogMayaResultBinding

class MayaResultDialog : DialogFragment() {
    private val binding by viewBinding(DialogMayaResultBinding::bind)
    var onMayaResultDialogButtonsClickListener: MayaBaseResultDialogButtonsClickListener? = null

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
        return inflater.inflate(R.layout.dialog_maya_result, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val type = arguments?.getInt("Type")
        val sourceCurrency = arguments?.getString(ARG_SOURCE) ?: Constants.DASH_CURRENCY
        val destinationCurrency = arguments?.getString(ARG_DESTINATION) ?: getString(R.string.error)
        type?.let {
            when (type) {
                Type.PURCHASE_ERROR.ordinal -> setPurchaseError()
                Type.DEPOSIT_ERROR.ordinal -> setDepositError()
                Type.DEPOSIT_SUCCESS.ordinal -> setDepositSuccess()
                Type.CONVERSION_SUCCESS.ordinal -> setConversionSuccess(
                    sourceCurrency,
                    destinationCurrency
                )
                Type.CONVERSION_ERROR.ordinal -> setConversionError()
                Type.SWAP_ERROR.ordinal -> setSwapError()
                Type.TRANSFER_DASH_SUCCESS.ordinal -> setTransferDashSuccess(false)
                Type.TRANSFER_DASH_ERROR.ordinal -> setTransferDashFailure()
            }

            binding.coinbaseBuyDialogPositiveButton.setOnClickListener {
                onMayaResultDialogButtonsClickListener?.onPositiveButtonClick(
                    Type.values().first { it.ordinal == type }
                )
            }
        }

//        binding.coinbaseBuyDialogNegativeButton.setOnClickListener {
//            type?.let {
//                onMayaResultDialogButtonsClickListener?.onNegativeButtonClick(
//                    Type.values().first { it.ordinal == type }
//                )
//            }
//            dismiss()
//            findNavController().popBackStack()
//            findNavController().popBackStack()
//        }

        binding.buyDialogContactCoinbaseSupport.setOnClickListener {
            openCoinbaseHelp()
        }
    }

    private fun setPurchaseError() {
        binding.coinbaseBuyDialogIcon.setImageResource(R.drawable.ic_error)
        binding.coinbaseBuyDialogTitle.setText(R.string.purchase_failed)
        binding.coinbaseBuyDialogTitle.setTextAppearance(R.style.Headline5_Red)
        val errorMessage = arguments?.getString(ARG_MESSAGE)
        if (errorMessage.isNullOrEmpty()) {
            binding.coinbaseBuyDialogMessage.setText(R.string.something_wrong_title)
        } else {
            binding.coinbaseBuyDialogMessage.text = errorMessage
        }
        binding.buyDialogContactCoinbaseSupport.isGone = true
        binding.coinbaseBuyDialogPositiveButton.setText(R.string.button_close)
    }

    private fun setDepositError() {
        binding.coinbaseBuyDialogIcon.setImageResource(R.drawable.ic_error)
        binding.coinbaseBuyDialogTitle.setText(R.string.transfer_failed)
        val errorMessage = arguments?.getString(ARG_MESSAGE)
        when {
            errorMessage.isNullOrEmpty() -> {
                binding.coinbaseBuyDialogMessage.setText(R.string.transfer_failed_msg)
            }
            errorMessage.contains(getString(R.string.send_to_wallet_error)) -> {
                binding.coinbaseBuyDialogMessage.text = errorMessage
            }
            else -> binding.coinbaseBuyDialogMessage.setText(R.string.transfer_failed_msg)
        }

        binding.coinbaseBuyDialogTitle.setTextAppearance(R.style.Headline5_Red)
        binding.buyDialogContactCoinbaseSupport.isVisible = true
        binding.coinbaseBuyDialogPositiveButton.setText(R.string.button_retry)
    }

    private fun setDepositSuccess() {
        binding.coinbaseBuyDialogIcon.setImageResource(R.drawable.ic_success_green_white_border)
        binding.coinbaseBuyDialogTitle.setText(R.string.purchase_successful)
        binding.coinbaseBuyDialogTitle.setTextAppearance(R.style.Headline5_Green)
        binding.coinbaseBuyDialogMessage.setText(R.string.maya_it_could_take_up_to_2_3_minutes)
        binding.buyDialogContactCoinbaseSupport.isGone = true
        binding.coinbaseBuyDialogPositiveButton.setText(R.string.button_close)
    }

    private fun setConversionError() {
        binding.coinbaseBuyDialogIcon.setImageResource(R.drawable.ic_error)
        binding.coinbaseBuyDialogTitle.setText(R.string.conversion_failed)
        binding.coinbaseBuyDialogMessage.setText(R.string.something_wrong_title)
        binding.coinbaseBuyDialogTitle.setTextAppearance(R.style.Headline5_Red)
        binding.buyDialogContactCoinbaseSupport.isVisible = true
        binding.coinbaseBuyDialogPositiveButton.setText(R.string.button_retry)
    }

    private fun setSwapError() {
        binding.coinbaseBuyDialogIcon.setImageResource(R.drawable.ic_error)
        binding.coinbaseBuyDialogTitle.setText(R.string.conversion_failed)
        binding.coinbaseBuyDialogMessage.setText(R.string.something_wrong_title)
        binding.coinbaseBuyDialogTitle.setTextAppearance(R.style.Headline5_Red)
        binding.buyDialogContactCoinbaseSupport.isVisible = true
        binding.coinbaseBuyDialogPositiveButton.isVisible = false
    }

    private fun setConversionSuccess(source: String, destination: String) {
        binding.coinbaseBuyDialogIcon.setImageResource(R.drawable.ic_success_green_white_border)
        binding.coinbaseBuyDialogTitle.setText(R.string.conversion_successful)
        binding.coinbaseBuyDialogTitle.setTextAppearance(R.style.Headline5_Green)
        binding.coinbaseBuyDialogMessage.text = getString(
            R.string.maya_it_could_take_up_to_5_minutes,
            source,
            destination
        )
        binding.buyDialogContactCoinbaseSupport.isGone = true
        binding.coinbaseBuyDialogPositiveButton.setText(R.string.button_close)
    }

    private fun setTransferDashSuccess(dashToCoinbase: Boolean) {
        binding.coinbaseBuyDialogIcon.setImageResource(R.drawable.ic_success_green_white_border)
        binding.coinbaseBuyDialogTitle.setText(R.string.transfer_dash_successful)
        binding.coinbaseBuyDialogTitle.setTextAppearance(R.style.Headline5_Green)
        binding.coinbaseBuyDialogMessage.setText(
            if (dashToCoinbase) {
                R.string.maya_it_could_take_up_to_10_minutes_to_coinbase
            } else {
                R.string.maya_it_could_take_up_to_10_minutes
            }
        )
        binding.buyDialogContactCoinbaseSupport.isGone = true
        binding.coinbaseBuyDialogPositiveButton.setText(R.string.button_close)
    }

    private fun setTransferDashFailure() {
        binding.coinbaseBuyDialogIcon.setImageResource(R.drawable.ic_error)
        binding.coinbaseBuyDialogTitle.setText(R.string.transfer_failed)
        val errorMessage = arguments?.getString(ARG_MESSAGE)
        when {
            errorMessage.isNullOrEmpty() -> {
                binding.coinbaseBuyDialogMessage.setText(R.string.transfer_dash_failed_msg)
            }
            else -> binding.coinbaseBuyDialogMessage.text = errorMessage
        }

        binding.coinbaseBuyDialogTitle.setTextAppearance(R.style.Headline5_Red)
        binding.buyDialogContactCoinbaseSupport.isVisible = true
        binding.coinbaseBuyDialogPositiveButton.setText(R.string.button_retry)
    }

    companion object {
        const val ARG_MESSAGE: String = "ARG_RESPONSE_MESSAGE"
        const val ARG_SOURCE: String = "ARG_SOURCE"
        const val ARG_DESTINATION: String = "ARG_DESTINATION"
        fun newInstance(
            type: Type,
            responseMessage: String?,
            sourceCurrency: String?,
            destinationCurrency: String?
        ): MayaResultDialog {
            val args = Bundle().apply {
                putInt("Type", type.ordinal)
                putString(ARG_MESSAGE, responseMessage)
                putString(ARG_SOURCE, sourceCurrency)
                putString(ARG_DESTINATION, destinationCurrency)
            }
            return MayaResultDialog().apply {
                arguments = args
            }
        }
    }

    enum class Type {
        DEPOSIT_SUCCESS,
        DEPOSIT_ERROR,
        PURCHASE_ERROR,
        CONVERSION_SUCCESS,
        CONVERSION_ERROR,
        SWAP_ERROR,
        TRANSFER_DASH_SUCCESS,
        TRANSFER_DASH_ERROR
    }

    interface MayaBaseResultDialogButtonsClickListener {
        fun onPositiveButtonClick(type: Type)
        fun onNegativeButtonClick(type: Type) {}
    }

    private fun openCoinbaseHelp() {
        val helpUrl = "https://help.coinbase.com/en/contact-us"
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse(helpUrl)
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(requireActivity(), helpUrl, Toast.LENGTH_SHORT).show()
        }
    }
}
