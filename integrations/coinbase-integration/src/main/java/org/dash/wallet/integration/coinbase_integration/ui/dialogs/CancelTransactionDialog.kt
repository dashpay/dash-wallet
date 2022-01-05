package org.dash.wallet.integration.coinbase_integration.ui.dialogs

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import org.dash.wallet.common.UserInteractionAwareCallback
import org.dash.wallet.common.ui.LockScreenViewModel
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.integration.coinbase_integration.R
import org.dash.wallet.integration.coinbase_integration.databinding.DialogCancelTransactionBinding

class CancelTransactionDialog: DialogFragment() {
    private val lockScreenViewModel: LockScreenViewModel by activityViewModels()
    private val binding by viewBinding(DialogCancelTransactionBinding::bind)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            requestFeature(Window.FEATURE_NO_TITLE)
        }
        return inflater.inflate(R.layout.dialog_cancel_transaction, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        lockScreenViewModel.activatingLockScreen.observe(viewLifecycleOwner){
            dismiss()
        }
        binding.negativeButton.setOnClickListener {
            findNavController().navigateUp()
            findNavController().popBackStack()
        }
        binding.positiveButton.setOnClickListener {
            findNavController().navigateUp()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.apply {
            window?.apply {
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                callback = UserInteractionAwareCallback(this.callback, requireActivity())
            }
        }
    }
}