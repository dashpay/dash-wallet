/*
 * Copyright 2023 Dash Core Group.
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

package org.dash.wallet.features.exploredash.ui.ctxspend.dialogs

import android.os.Bundle
import android.view.View
import androidx.annotation.StyleRes
import androidx.fragment.app.FragmentActivity
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.databinding.DialogCtxSpendLoginInfoBinding

class CTXSpendLoginInfoDialog : OffsetDialogFragment(R.layout.dialog_ctx_spend_login_info) {

    private var onExtraMessageListener: (() -> Unit)? = null
    private var onResultListener: ((Boolean?) -> Unit)? = null

    @StyleRes
    override val backgroundStyle = R.style.PrimaryBackground

    private val binding by viewBinding(DialogCtxSpendLoginInfoBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.dialogPositiveButton.setOnClickListener { onPositiveAction() }
        binding.dialogNegativeButton.setOnClickListener { onNegativeAction() }
        binding.dialogExtraMessage.setOnClickListener { onExtraMessageAction() }
    }

    private fun onPositiveAction() {
        onResultListener?.invoke(true)
        onResultListener = null
        dismiss()
    }

    private fun onNegativeAction() {
        onResultListener?.invoke(false)
        onResultListener = null
        dismiss()
    }

    fun show(activity: FragmentActivity, onResult: (Boolean?) -> Unit, onExtraMessageAction: () -> Unit) {
        onResultListener = onResult
        onExtraMessageListener = onExtraMessageAction
        show(activity)
    }

    private fun onExtraMessageAction() {
        onExtraMessageListener?.invoke()
        onExtraMessageListener = null
    }
}