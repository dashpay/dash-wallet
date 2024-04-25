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

package org.dash.wallet.integrations.maya.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.Intent.ACTION_VIEW
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import org.dash.wallet.common.ui.LockScreenAware
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.integrations.maya.R
import org.dash.wallet.integrations.maya.databinding.MayaConvertResultFragmentBinding
import org.dash.wallet.integrations.maya.model.TransactionType
import org.dash.wallet.integrations.maya.ui.dialogs.MayaResultDialog

@AndroidEntryPoint
class MayaConvertResultFragment : Fragment(R.layout.maya_convert_result_fragment), LockScreenAware {

    private val binding by viewBinding(MayaConvertResultFragmentBinding::bind)
    private val viewModel by viewModels<MayaConvertResultViewModel>()
    private lateinit var loadingDialog: AdaptiveDialog
    private var onBackPressedCallback: OnBackPressedCallback? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        handleBackPress()
        val params = arguments?.let { MayaConvertResultFragmentArgs.fromBundle(it).transactionParams }
        viewModel.sendInitialTransactionToSMSTwoFactorAuth(params?.params)

        viewModel.loadingState.observe(viewLifecycleOwner) {
            // setLoadingState(it)
        }

        viewModel.transactionState.observe(viewLifecycleOwner) { state ->
            params?.let { setTransactionState(it.type, state) }
        }

        binding.contactCoinbaseSupport.setOnClickListener {
            openMayaHelp()
        }

        viewModel.verifyUserAndCompleteTransaction(params?.params, "")
    }

    private fun openMayaHelp() {
        val helpUrl = "https://www.mayaprotocol.com/contact"
        try {
            val intent = Intent(ACTION_VIEW)
            intent.data = Uri.parse(helpUrl)
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(requireActivity(), helpUrl, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setTransactionState(transactionType: TransactionType, state: TransactionState) {
        if (state.isTransactionSuccessful) {
            when (transactionType) {
                TransactionType.BuyDash -> showTransactionStateDialog(MayaResultDialog.Type.DEPOSIT_SUCCESS)
                TransactionType.BuySwap -> showTransactionStateDialog(MayaResultDialog.Type.CONVERSION_SUCCESS)
                TransactionType.TransferDash -> showTransactionStateDialog(MayaResultDialog.Type.TRANSFER_DASH_SUCCESS)
                TransactionType.SellSwap -> showTransactionStateDialog(MayaResultDialog.Type.CONVERSION_SUCCESS)
            }
        } else {
            when (transactionType) {
                TransactionType.BuyDash -> showTransactionStateDialog(
                    MayaResultDialog.Type.DEPOSIT_ERROR,
                    state.responseMessage
                )
                TransactionType.BuySwap -> showTransactionStateDialog(
                    MayaResultDialog.Type.TRANSFER_DASH_ERROR,
                    state.responseMessage
                )
                TransactionType.TransferDash -> showTransactionStateDialog(
                    MayaResultDialog.Type.TRANSFER_DASH_ERROR,
                    state.responseMessage
                )
                else -> {}
            }
        }
    }

    private fun setLoadingState(showLoading: Boolean) {
        if (showLoading) {
            showProgressDialog()
        } else {
            hideProgressDialog()
        }
    }

    private fun handleBackPress() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().previousBackStackEntry?.savedStateHandle?.set("resume_review", true)
            findNavController().popBackStack()
        }

        onBackPressedCallback = requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            findNavController().previousBackStackEntry?.savedStateHandle?.set("resume_review", true)
            findNavController().popBackStack()
        }
    }

    private fun showProgressDialog() {
        if (::loadingDialog.isInitialized && loadingDialog.dialog?.isShowing == true) {
            loadingDialog.dismissAllowingStateLoss()
        }
        loadingDialog = AdaptiveDialog.progress(getString(R.string.loading))
        loadingDialog.show(parentFragmentManager, tag)
    }

    private fun hideProgressDialog() {
        if (loadingDialog.isAdded) {
            loadingDialog.dismissAllowingStateLoss()
        }
    }

    private fun showTransactionStateDialog(type: MayaResultDialog.Type, responseMessage: String? = null) {
        val params = arguments?.let { MayaConvertResultFragmentArgs.fromBundle(it).transactionParams }
        val transactionStateDialog = MayaResultDialog.newInstance(
            type,
            responseMessage,
            params?.coinbaseWalletName,
            destinationCurrency = params?.params?.amount?.cryptoCode
        ).apply {
            this.onMayaResultDialogButtonsClickListener =
                object : MayaResultDialog.MayaBaseResultDialogButtonsClickListener {
                    override fun onPositiveButtonClick(type: MayaResultDialog.Type) {
                        when (type) {
                            MayaResultDialog.Type.TRANSFER_DASH_ERROR, MayaResultDialog.Type.DEPOSIT_ERROR -> {
                                viewModel.logRetry(type)
                                viewModel.isRetryingTransfer(true)
                                dismiss()
                            }
                            MayaResultDialog.Type.CONVERSION_ERROR -> {
                                viewModel.logRetry(type)
                                dismiss()
                                findNavController().popBackStack()
                            }
                            MayaResultDialog.Type.CONVERSION_SUCCESS, MayaResultDialog.Type.DEPOSIT_SUCCESS, MayaResultDialog.Type.TRANSFER_DASH_SUCCESS -> {
                                viewModel.logClose(type)
                                dismiss()
                                val navController = findNavController()
                                val home = navController.graph.startDestinationId
                                navController.popBackStack(home, false)
                            }
                            else -> {}
                        }
                    }

                    override fun onNegativeButtonClick(type: MayaResultDialog.Type) {
                        viewModel.logClose(type)
                    }
                }
        }
        transactionStateDialog.showNow(parentFragmentManager, "MayaSwapDashDialog")
    }

    override fun onLockScreenActivated() {
        findNavController().popBackStack(R.id.mayaPortalFragment, false)
    }

    override fun onDestroy() {
        super.onDestroy()
        onBackPressedCallback?.remove()
    }
}
