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

package de.schildbach.wallet.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.activityViewModels
import de.schildbach.wallet_test.databinding.DialogTransactionsFilterBinding
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.FirebaseAnalyticsServiceImpl
import org.dash.wallet.common.ui.BaseBottomSheetDialogFragment

class TransactionsFilterDialog : BaseBottomSheetDialogFragment() {

    private val sharedViewModel: TransactionsFilterSharedViewModel by activityViewModels()
    private lateinit var binding: DialogTransactionsFilterBinding
    private val analytics = FirebaseAnalyticsServiceImpl.getInstance()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        super.onCreate(savedInstanceState)
        binding = DialogTransactionsFilterBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // take care of actions here
        view.apply {
            binding.allTransactions.setOnClickListener {
                sharedViewModel.onAllTransactionsSelected.call()
                analytics.logEvent(AnalyticsConstants.Home.TRANSACTION_FILTER, bundleOf(
                    "filter_value" to "all_transactions"
                ))
                dismiss()
            }
            binding.receivedTransactions.setOnClickListener {
                sharedViewModel.onReceivedTransactionsSelected.call()
                analytics.logEvent(AnalyticsConstants.Home.TRANSACTION_FILTER, bundleOf(
                    "filter_value" to "received_transactions"
                ))
                dismiss()
            }
            binding.sentTransactions.setOnClickListener {
                sharedViewModel.onSentTransactionsSelected.call()
                analytics.logEvent(AnalyticsConstants.Home.TRANSACTION_FILTER, bundleOf(
                    "filter_value" to "sent_transactions"
                ))
                dismiss()
            }
        }
    }
}
