package de.schildbach.wallet.ui.more.tools

import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.more.ToolsViewModel
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.DialogWhatAreCreditsBinding
import kotlinx.coroutines.launch
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding

@AndroidEntryPoint
class WhatAreCreditsDialogFragment : OffsetDialogFragment(R.layout.dialog_what_are_credits) {
    private val binding by viewBinding(DialogWhatAreCreditsBinding::bind)
    val viewModel by viewModels<ToolsViewModel>()
    private var onDismissAction: (() -> Unit)? = null
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.closeButton.setOnClickListener {
            dismiss()
        }
    }

    override fun dismiss() {
        lifecycleScope.launch {
            viewModel.setCreditsExplained()
            onDismissAction?.invoke()
            super.dismiss()
        }
    }

    fun show(fragmentActivity: FragmentActivity, onDismissAction: () -> Unit) {
        this.onDismissAction = onDismissAction
        show(fragmentActivity)
    }
}