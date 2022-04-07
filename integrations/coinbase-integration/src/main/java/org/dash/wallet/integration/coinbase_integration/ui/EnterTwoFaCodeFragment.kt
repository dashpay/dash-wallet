package org.dash.wallet.integration.coinbase_integration.ui

import android.os.Bundle
import android.view.View
import androidx.activity.addCallback
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import org.dash.wallet.common.Constants
import org.dash.wallet.common.ui.FancyAlertDialog
import org.dash.wallet.common.ui.enter_amount.NumericKeyboardView
import org.dash.wallet.common.ui.getRoundedBackground
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.integration.coinbase_integration.R
import org.dash.wallet.integration.coinbase_integration.databinding.EnterTwoFaCodeFragmentBinding
import org.dash.wallet.integration.coinbase_integration.model.TransactionType
import org.dash.wallet.integration.coinbase_integration.ui.dialogs.CoinBaseBuyDashDialog
import org.dash.wallet.integration.coinbase_integration.viewmodels.EnterTwoFaCodeViewModel
import org.dash.wallet.integration.coinbase_integration.viewmodels.TransactionState

@AndroidEntryPoint
class EnterTwoFaCodeFragment : Fragment(R.layout.enter_two_fa_code_fragment) {

    private val binding by viewBinding(EnterTwoFaCodeFragmentBinding::bind)
    private val viewModel by viewModels<EnterTwoFaCodeViewModel>()
    private lateinit var loadingDialog: FancyAlertDialog

    companion object {
        fun newInstance() = EnterTwoFaCodeFragment()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        handleBackPress()
        val params = arguments?.let { EnterTwoFaCodeFragmentArgs.fromBundle(it).transactionParams }

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
            binding.enterCodeField.background = resources.getRoundedBackground(org.dash.wallet.common.R.style.TransparentRedBackground)
            binding.incorrectCodeGroup.isVisible = true
            binding.enterCodeDetails.isVisible = false
        }
    }

    private fun setTransactionState(transactionType: TransactionType, state: TransactionState) {
       if (state.isTransactionSuccessful){
           when(transactionType){
               TransactionType.BuyDash -> showTransactionStateDialog(CoinBaseBuyDashDialog.Type.TRANSFER_SUCCESS)
               TransactionType.BuySwap -> showTransactionStateDialog(CoinBaseBuyDashDialog.Type.CONVERSION_SUCCESS)
               else -> {}
           }
       } else {
           when(transactionType){
               TransactionType.BuyDash -> showTransactionStateDialog(CoinBaseBuyDashDialog.Type.TRANSFER_ERROR, state.responseMessage)
               TransactionType.BuySwap -> showTransactionStateDialog(CoinBaseBuyDashDialog.Type.CONVERSION_ERROR, state.responseMessage)
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
        binding.enterCodeField.text = value
        binding.verifyBtn.isEnabled = value.isNotEmpty()
    }


    private fun handleBackPress() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner){
            findNavController().popBackStack()
        }
    }

    private fun showProgressDialog(){
        if (::loadingDialog.isInitialized && loadingDialog.dialog?.isShowing == true){
            loadingDialog.dismissAllowingStateLoss()
        }
        loadingDialog = FancyAlertDialog.newProgress(R.string.loading)
        loadingDialog.show(parentFragmentManager, tag)
    }

    private fun hideProgressDialog(){
        if (loadingDialog.isAdded){
            loadingDialog.dismissAllowingStateLoss()
        }
    }

    private fun showTransactionStateDialog(type: CoinBaseBuyDashDialog.Type, responseMessage: String? = null) {
        val transactionStateDialog = CoinBaseBuyDashDialog.newInstance(type, responseMessage).apply {
            this.onCoinBaseBuyDashDialogButtonsClickListener =
                object : CoinBaseBuyDashDialog.CoinBaseBuyDashDialogButtonsClickListener {
                    override fun onPositiveButtonClick(type: CoinBaseBuyDashDialog.Type) {
                        when (type) {
                            CoinBaseBuyDashDialog.Type.CONVERSION_ERROR, CoinBaseBuyDashDialog.Type.TRANSFER_ERROR -> {
                                dismiss()
                                findNavController().popBackStack()
                            }
                            CoinBaseBuyDashDialog.Type.CONVERSION_SUCCESS, CoinBaseBuyDashDialog.Type.TRANSFER_SUCCESS -> {
                                dismiss()
                                requireActivity().setResult(Constants.RESULT_CODE_GO_HOME)
                                requireActivity().finish()
                            }
                            else -> {}
                        }
                    }
                }
        }
        transactionStateDialog.showNow(parentFragmentManager, "CoinBaseBuyDashDialog")
    }
}