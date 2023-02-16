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

package org.dash.wallet.integration.coinbase_integration.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.Intent.ACTION_VIEW
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.addCallback
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.enter_amount.NumericKeyboardView
import org.dash.wallet.common.ui.getRoundedBackground
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.integration.coinbase_integration.R
import org.dash.wallet.integration.coinbase_integration.databinding.EnterTwoFaCodeFragmentBinding
import org.dash.wallet.integration.coinbase_integration.model.TransactionType
import org.dash.wallet.integration.coinbase_integration.ui.dialogs.CoinBaseResultDialog
import org.dash.wallet.integration.coinbase_integration.viewmodels.EnterTwoFaCodeViewModel
import org.dash.wallet.integration.coinbase_integration.viewmodels.TransactionState

@AndroidEntryPoint
class EnterTwoFaCodeFragment : Fragment(R.layout.enter_two_fa_code_fragment) {

    private val binding by viewBinding(EnterTwoFaCodeFragmentBinding::bind)
    private val viewModel by viewModels<EnterTwoFaCodeViewModel>()
    private lateinit var loadingDialog: AdaptiveDialog

    companion object {
        fun newInstance() = EnterTwoFaCodeFragment()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        handleBackPress()
        val params = arguments?.let { EnterTwoFaCodeFragmentArgs.fromBundle(it).transactionParams }
        viewModel.sendInitialTransactionToSMSTwoFactorAuth(params?.params)

        binding.verifyBtn.setOnClickListener {
            viewModel.verifyUserAndCompleteTransaction(params?.params, binding.enterCodeField.text.toString())
        }
        binding.keyboardView.onKeyboardActionListener = keyboardActionListener

        viewModel.loadingState.observe(viewLifecycleOwner){
            setLoadingState(it)
        }

        viewModel.transactionState.observe(viewLifecycleOwner){ state ->
            params?.let { setTransactionState(it.type, state) }
        }

        viewModel.twoFaErrorState.observe(viewLifecycleOwner){
            binding.enterCodeField.background = resources.getRoundedBackground(org.dash.wallet.common.R.style.InputErrorBackground)
            binding.incorrectCodeGroup.isVisible = true
            binding.enterCodeDetails.isVisible = false
        }

        binding.contactCoinbaseSupport.setOnClickListener {
            openCoinbaseHelp()
        }

        binding.enterCodeField.doOnTextChanged { text, _, _, _ ->
            binding.verifyBtn.isEnabled = !text.isNullOrEmpty()
        }
    }

    private fun openCoinbaseHelp() {
        val helpUrl = "https://help.coinbase.com/en/contact-us"
        try {
            val intent = Intent(ACTION_VIEW)
            intent.data = Uri.parse(helpUrl)
            startActivity(intent)
        }catch (e: ActivityNotFoundException){
            Toast.makeText(requireActivity(), helpUrl, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setTransactionState(transactionType: TransactionType, state: TransactionState) {
       if (state.isTransactionSuccessful){
           when(transactionType){
               TransactionType.BuyDash -> showTransactionStateDialog(CoinBaseResultDialog.Type.DEPOSIT_SUCCESS)
               TransactionType.BuySwap -> showTransactionStateDialog(CoinBaseResultDialog.Type.CONVERSION_SUCCESS)
               TransactionType.TransferDash -> showTransactionStateDialog(CoinBaseResultDialog.Type.TRANSFER_DASH_SUCCESS)
               else -> {}
           }
       } else {
           when(transactionType){
               TransactionType.BuyDash -> showTransactionStateDialog(CoinBaseResultDialog.Type.DEPOSIT_ERROR, state.responseMessage)
               TransactionType.BuySwap -> showTransactionStateDialog(CoinBaseResultDialog.Type.TRANSFER_DASH_ERROR, state.responseMessage)
               TransactionType.TransferDash -> showTransactionStateDialog(CoinBaseResultDialog.Type.TRANSFER_DASH_ERROR, state.responseMessage)
               else -> {}
           }
       }
    }

    private fun setLoadingState(showLoading: Boolean) {
        if (showLoading){
            showProgressDialog()
        } else {
            hideProgressDialog()
        }
    }

    private val keyboardActionListener = object : NumericKeyboardView.OnKeyboardActionListener {
        var value = StringBuilder()

        fun refreshValue(){
            value.clear()
            value.append(binding.enterCodeField.text.toString())
        }

        override fun onNumber(number: Int) {
            refreshValue()
            value.append(number)
            applyNewValue(value.toString())
        }

        override fun onBack(longClick: Boolean) {
            refreshValue()
            if (longClick){
                value.clear()
            } else if (value.isNotEmpty()){
                value.deleteCharAt(value.length - 1)
            }
            applyNewValue(value.toString())
        }

        override fun onFunction() {}
    }

    private fun applyNewValue(value: String) {
        binding.enterCodeField.setText( value)
    }


    private fun handleBackPress() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().previousBackStackEntry?.savedStateHandle?.set("resume_review", true)
            findNavController().popBackStack()
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner){
            findNavController().previousBackStackEntry?.savedStateHandle?.set("resume_review", true)
            findNavController().popBackStack()
        }
    }

    private fun showProgressDialog(){
        if (::loadingDialog.isInitialized && loadingDialog.dialog?.isShowing == true){
            loadingDialog.dismissAllowingStateLoss()
        }
        loadingDialog = AdaptiveDialog.progress(getString(R.string.loading))
        loadingDialog.show(parentFragmentManager, tag)
    }

    private fun hideProgressDialog(){
        if (loadingDialog.isAdded){
            loadingDialog.dismissAllowingStateLoss()
        }
    }

    private fun showTransactionStateDialog(type: CoinBaseResultDialog.Type, responseMessage: String? = null) {
        val params = arguments?.let { EnterTwoFaCodeFragmentArgs.fromBundle(it).transactionParams }
        val transactionStateDialog = CoinBaseResultDialog.newInstance(type, responseMessage,params?.coinbaseWalletName, dashToCoinbase = false).apply {
            this.onCoinBaseResultDialogButtonsClickListener =
                object : CoinBaseResultDialog.CoinBaseResultDialogButtonsClickListener {
                    override fun onPositiveButtonClick(type: CoinBaseResultDialog.Type) {
                        when (type) {
                            CoinBaseResultDialog.Type.TRANSFER_DASH_ERROR , CoinBaseResultDialog.Type.DEPOSIT_ERROR-> {
                                viewModel.logRetry(type)
                                viewModel.isRetryingTransfer(true)
                                dismiss()
                                binding.enterCodeField.text?.clear()
                            }
                            CoinBaseResultDialog.Type.CONVERSION_ERROR-> {
                                viewModel.logRetry(type)
                                dismiss()
                                findNavController().popBackStack()
                            }
                            CoinBaseResultDialog.Type.CONVERSION_SUCCESS, CoinBaseResultDialog.Type.DEPOSIT_SUCCESS, CoinBaseResultDialog.Type.TRANSFER_DASH_SUCCESS -> {
                                viewModel.logClose(type)
                                dismiss()
                                requireActivity().setResult(Constants.RESULT_CODE_GO_HOME)
                                requireActivity().finish()
                            }
                            else -> {}
                        }
                    }

                    override fun onNegativeButtonClick(type: CoinBaseResultDialog.Type) {
                        viewModel.logClose(type)
                    }
                }
        }
        transactionStateDialog.showNow(parentFragmentManager, "CoinBaseBuyDashDialog")
    }
}
