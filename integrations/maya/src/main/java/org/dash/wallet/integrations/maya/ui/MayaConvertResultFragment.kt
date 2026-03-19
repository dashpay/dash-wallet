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

package org.dash.wallet.integrations.maya.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.Intent.ACTION_VIEW
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.core.net.toUri
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import org.dash.wallet.common.ui.LockScreenAware
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.integrations.maya.R
import org.dash.wallet.integrations.maya.databinding.MayaConvertResultFragmentBinding
import org.dash.wallet.integrations.maya.model.MayaResultType
import org.dash.wallet.integrations.maya.model.TransactionType
import org.dash.wallet.integrations.maya.ui.dialogs.MayaResultDialog

@AndroidEntryPoint
class MayaConvertResultFragment : Fragment(R.layout.maya_convert_result_fragment), LockScreenAware {

    private val binding by viewBinding(MayaConvertResultFragmentBinding::bind)
    private val viewModel by viewModels<MayaConvertResultViewModel>()
    private var onBackPressedCallback: OnBackPressedCallback? = null
    private var currentType: MayaResultType? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        handleBackPress()

        val params = arguments?.let { MayaConvertResultFragmentArgs.fromBundle(it).transactionParams }

        viewModel.loadingState.observe(viewLifecycleOwner) { isLoading ->
            binding.progressRing.isVisible = isLoading
        }

        viewModel.transactionState.observe(viewLifecycleOwner) { state ->
            params?.let { setTransactionState(it.type, state) }
        }

        binding.contactSupport.setOnClickListener {
            openMayaHelp()
        }

        binding.coinbaseBuyDialogPositiveButton.setOnClickListener {
            handlePositiveButtonClick()
        }
    }

    private fun handlePositiveButtonClick() {
        val type = currentType ?: return
        when (type) {
            MayaResultType.TRANSFER_DASH_ERROR,
            MayaResultType.DEPOSIT_ERROR -> {
                viewModel.logRetry(type)
                viewModel.isRetryingTransfer(true)
            }
            MayaResultType.CONVERSION_ERROR -> {
                viewModel.logRetry(type)
                findNavController().popBackStack()
            }
            MayaResultType.CONVERSION_SUCCESS,
            MayaResultType.DEPOSIT_SUCCESS,
            MayaResultType.TRANSFER_DASH_SUCCESS -> {
                viewModel.logClose(type)
                val navController = findNavController()
                navController.popBackStack(navController.graph.startDestinationId, false)
            }
            else -> {}
        }
    }

    private fun openMayaHelp() {
        val helpUrl = "https://www.mayaprotocol.com"
        try {
            val intent = Intent(ACTION_VIEW)
            intent.data = helpUrl.toUri()
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(requireActivity(), helpUrl, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setTransactionState(transactionType: TransactionType, state: TransactionState) {
        binding.progressRing.isGone = true
        binding.resultContent.isVisible = true
        binding.coinbaseBuyDialogPositiveButton.isVisible = true

        if (state.isTransactionSuccessful) {
            when (transactionType) {
                TransactionType.BuyDash -> setDepositSuccess()
                TransactionType.BuySwap -> setConversionSuccess()
                TransactionType.TransferDash -> setTransferDashSuccess()
                TransactionType.SellSwap -> setConversionSuccess()
            }
        } else {
            when (transactionType) {
                TransactionType.BuyDash -> setDepositError(state.responseMessage)
                TransactionType.BuySwap -> setTransferDashError(state.responseMessage)
                TransactionType.TransferDash -> setTransferDashError(state.responseMessage)
                TransactionType.SellSwap -> setSellSwapError(state.responseMessage)
            }
        }
    }

    private fun setDepositSuccess() {
        currentType = MayaResultType.DEPOSIT_SUCCESS
        binding.coinbaseBuyDialogIcon.setImageResource(R.drawable.ic_success_green_white_border)
        binding.coinbaseBuyDialogTitle.setText(R.string.purchase_successful)
        binding.coinbaseBuyDialogTitle.setTextAppearance(R.style.Headline5_Green)
        binding.coinbaseBuyDialogMessage.setText(R.string.maya_it_could_take_up_to_2_3_minutes)
        binding.contactSupport.isGone = true
        binding.coinbaseBuyDialogPositiveButton.setText(R.string.button_close)
    }

    private fun setDepositError(errorMessage: String?) {
        currentType = MayaResultType.DEPOSIT_ERROR
        binding.coinbaseBuyDialogIcon.setImageResource(R.drawable.ic_error)
        binding.coinbaseBuyDialogTitle.setText(R.string.transfer_failed)
        binding.coinbaseBuyDialogTitle.setTextAppearance(R.style.Headline5_Red)
        when {
            errorMessage.isNullOrEmpty() -> binding.coinbaseBuyDialogMessage.setText(R.string.transfer_failed_msg)
            errorMessage.contains(getString(R.string.send_to_wallet_error)) -> binding.coinbaseBuyDialogMessage.text = errorMessage
            else -> binding.coinbaseBuyDialogMessage.setText(R.string.transfer_failed_msg)
        }
        binding.contactSupport.isVisible = true
        binding.coinbaseBuyDialogPositiveButton.setText(R.string.button_retry)
    }

    private fun setConversionSuccess() {
        currentType = MayaResultType.CONVERSION_SUCCESS
        val params = arguments?.let { MayaConvertResultFragmentArgs.fromBundle(it).transactionParams }
        val source = params?.coinbaseWalletName ?: org.dash.wallet.common.util.Constants.DASH_CURRENCY
        val destination = params?.params?.amount?.cryptoCode ?: getString(R.string.error)
        binding.coinbaseBuyDialogIcon.setImageResource(R.drawable.ic_success_green_white_border)
        binding.coinbaseBuyDialogTitle.setText(R.string.conversion_successful)
        binding.coinbaseBuyDialogTitle.setTextAppearance(R.style.Headline5_Green)
        binding.coinbaseBuyDialogMessage.text = getString(R.string.maya_it_could_take_up_to_5_minutes, source, destination)
        binding.contactSupport.isGone = true
        binding.coinbaseBuyDialogPositiveButton.setText(R.string.button_close)
    }

    private fun setTransferDashSuccess() {
        currentType = MayaResultType.TRANSFER_DASH_SUCCESS
        binding.coinbaseBuyDialogIcon.setImageResource(R.drawable.ic_success_green_white_border)
        binding.coinbaseBuyDialogTitle.setText(R.string.transfer_dash_successful)
        binding.coinbaseBuyDialogTitle.setTextAppearance(R.style.Headline5_Green)
        binding.coinbaseBuyDialogMessage.setText(R.string.maya_it_could_take_up_to_10_minutes)
        binding.contactSupport.isGone = true
        binding.coinbaseBuyDialogPositiveButton.setText(R.string.button_close)
    }

    private fun setTransferDashError(errorMessage: String?) {
        currentType = MayaResultType.TRANSFER_DASH_ERROR
        binding.coinbaseBuyDialogIcon.setImageResource(R.drawable.ic_error)
        binding.coinbaseBuyDialogTitle.setText(R.string.transfer_failed)
        binding.coinbaseBuyDialogTitle.setTextAppearance(R.style.Headline5_Red)
        binding.coinbaseBuyDialogMessage.text = if (errorMessage.isNullOrEmpty()) {
            getString(R.string.transfer_dash_failed_msg)
        } else {
            errorMessage
        }
        binding.contactSupport.isVisible = true
        binding.coinbaseBuyDialogPositiveButton.setText(R.string.button_retry)
    }

    private fun setSellSwapError(errorMessage: String?) {
        currentType = MayaResultType.SWAP_ERROR
        binding.coinbaseBuyDialogIcon.setImageResource(R.drawable.ic_error)
        binding.coinbaseBuyDialogTitle.setText(R.string.conversion_failed)
        binding.coinbaseBuyDialogTitle.setTextAppearance(R.style.Headline5_Red)
        binding.coinbaseBuyDialogMessage.text = if (errorMessage.isNullOrEmpty()) {
            getString(R.string.transfer_failed_msg)
        } else {
            errorMessage
        }
        binding.contactSupport.isVisible = true
        binding.coinbaseBuyDialogPositiveButton.setText(R.string.button_retry)
    }

    private fun handleBackPress() {
        // Block back navigation to prevent re-submitting the transaction
        onBackPressedCallback = requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            // Intentionally consume back press without navigating for success states
            if (currentType != MayaResultType.TRANSFER_DASH_SUCCESS &&
                currentType != MayaResultType.CONVERSION_SUCCESS &&
                currentType != MayaResultType.DEPOSIT_SUCCESS) {
                findNavController().popBackStack()
            }
        }
    }

    override fun onLockScreenActivated() {
        findNavController().popBackStack(R.id.mayaPortalFragment, false)
    }

    override fun onDestroy() {
        super.onDestroy()
        onBackPressedCallback?.remove()
    }
}