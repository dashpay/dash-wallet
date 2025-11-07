/*
 * Copyright (c) 2025 Dash Core Group
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package de.schildbach.wallet.ui.more

import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.DialogTransactionMetadataBinding
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.dialogSafeNavigate

@AndroidEntryPoint
class TransactionMetadataDialog : OffsetDialogFragment(R.layout.dialog_transaction_metadata) {
    companion object {
        fun newInstance(): TransactionMetadataDialog {
            return TransactionMetadataDialog().apply {
                arguments = TransactionMetadataDialogArgs(firstTime = false, useNavigation = false).toBundle()
            }
        }
    }
    private val binding by viewBinding(DialogTransactionMetadataBinding::bind)
    @OptIn(ExperimentalCoroutinesApi::class)
    private val viewModel by viewModels<TransactionMetadataSettingsViewModel>()
    private val args by navArgs<TransactionMetadataDialogArgs>()
    private var onButtonClickListener: ((Boolean?) -> Unit)? = null
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.transactionMetadataCost.setOnClickListener {
            dialogSafeNavigate(TransactionMetadataDialogDirections.toCostDialog())
        }
        binding.saveDataButton.isVisible = args.firstTime
        binding.saveDataButton.setOnClickListener {
            if (args.useNavigation) {
                dialogSafeNavigate(TransactionMetadataDialogDirections.toSettingsFragment())
            } else {
                onButtonClickListener?.invoke(true)
            }
            dismiss()
        }
        lifecycleScope.launch {
            viewModel.setTransactionMetadataInfoShown()
        }
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        onButtonClickListener?.invoke(null)
    }

    fun show(activity: FragmentActivity, function: (Boolean?) -> Unit) {
        onButtonClickListener = function
        show(activity)
    }
}
