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

package de.schildbach.wallet.ui.dashpay

import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProviders
import de.schildbach.wallet.ui.BaseBottomSheetDialogFragment
import de.schildbach.wallet.ui.SingleActionSharedViewModel
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.dialog_new_account_confirm.*
import org.dash.wallet.common.util.GenericUtils

class NewAccountConfirmDialog : BaseBottomSheetDialogFragment() {

    companion object {

        @JvmStatic
        fun createDialog(): DialogFragment {
            return NewAccountConfirmDialog()
        }
    }

    private lateinit var sharedViewModel: SingleActionSharedViewModel

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.dialog_new_account_confirm, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        agree_check.setOnCheckedChangeListener { _, isChecked ->
            confirm.isEnabled = isChecked
        }

        confirm.setOnClickListener {
            dismiss()
            sharedViewModel.clickConfirmButtonEvent.call(true)
        }

        val creatingCost = SpannableStringBuilder("0.01").run {
            setSpan(StyleSpan(Typeface.BOLD), 0, length, 0)
//            setSpan(RelativeSizeSpan(1.1f), 0, length, 0)
            this
        }
        val costWithDashSymbol = GenericUtils.insertDashSymbol(context, creatingCost, 0, true, true, 1.1f)
        val builder = SpannableStringBuilder().run {
            append(getString(R.string.new_account_confirm_message_prefix))
            append(costWithDashSymbol)
            append(" ")
            append(getString(R.string.new_account_confirm_message_suffix))
            this
        }
        message.text = builder
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        sharedViewModel = activity?.run {
            ViewModelProviders.of(this)[SingleActionSharedViewModel::class.java]
        } ?: throw IllegalStateException("Invalid Activity")
    }
}
