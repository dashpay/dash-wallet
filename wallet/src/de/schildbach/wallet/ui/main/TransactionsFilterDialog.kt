/*
 * Copyright 2020 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import de.schildbach.wallet.transactions.TxDirection
import de.schildbach.wallet_test.databinding.DialogTransactionsFilterBinding
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment

class TransactionsFilterDialog(
    private val clickListener: (TxDirection, DialogFragment) -> Unit
) : OffsetDialogFragment() {
    private lateinit var binding: DialogTransactionsFilterBinding

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        super.onCreate(savedInstanceState)
        binding = DialogTransactionsFilterBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // take care of actions here
        binding.allTransactions.setOnClickListener {
            clickListener.invoke(TxDirection.ALL, this@TransactionsFilterDialog)
            dismiss()
        }
        binding.receivedTransactions.setOnClickListener {
            clickListener.invoke(TxDirection.RECEIVED, this@TransactionsFilterDialog)
            dismiss()
        }
        binding.sentTransactions.setOnClickListener {
            clickListener.invoke(TxDirection.SENT, this@TransactionsFilterDialog)
            dismiss()
        }
    }
}
