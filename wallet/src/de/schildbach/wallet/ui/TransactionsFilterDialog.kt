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
import android.widget.FrameLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.dialog_transactions_filter.view.*

class TransactionsFilterDialog : BaseBottomSheetDialogFragment() {

    private lateinit var sharedViewModel: TransactionsFilterSharedViewModel

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.dialog_transactions_filter, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // take care of actions here
        view.apply {
            all_transactions.setOnClickListener {
                sharedViewModel.onAllTransactionsSelected.call()
                dismiss()
            }
            received_transactions.setOnClickListener {
                sharedViewModel.onReceivedTransactionsSelected.call()
                dismiss()
            }
            sent_transactions.setOnClickListener {
                sharedViewModel.onSentTransactionsSelected.call()
                dismiss()
            }
        }

        dialog?.setOnShowListener { dialog ->
            // apply wrap_content height
            val d = dialog as BottomSheetDialog
            val bottomSheet = d.findViewById<FrameLayout>(R.id.design_bottom_sheet)
            val coordinatorLayout = bottomSheet!!.parent as CoordinatorLayout
            val bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)
            bottomSheetBehavior.peekHeight = bottomSheet.height
            coordinatorLayout.parent.requestLayout()
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        sharedViewModel = activity?.run {
            ViewModelProvider(this)[TransactionsFilterSharedViewModel::class.java]
        } ?: throw IllegalStateException("Invalid Activity")
    }
}
