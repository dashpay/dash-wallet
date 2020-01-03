/*
 * Copyright 2018 Dash Core Group
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

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet_test.R

class ResetWalletDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialogBuilder = AlertDialog.Builder(context!!, R.style.My_Theme_Dialog)
        dialogBuilder.setTitle(R.string.wallet_lock_reset_wallet_title)
        dialogBuilder.setMessage(R.string.wallet_lock_reset_wallet_message)
        //Inverting dialog answers to prevent accidental wallet reset
        dialogBuilder.setNegativeButton(R.string.wallet_lock_reset_wallet_title) { _, _ ->
            (activity as? AbstractBindServiceActivity)?.unbindServiceServiceConnection()
            WalletApplication.getInstance().triggerWipe(context)
        }
        dialogBuilder.setPositiveButton(android.R.string.no, null)
        dialogBuilder.setCancelable(false)
        val dialog = dialogBuilder.create()
        dialog.setCanceledOnTouchOutside(false)
        return dialog
    }

    companion object {
        @JvmStatic
        fun newInstance(): ResetWalletDialog {
            return ResetWalletDialog()
        }
    }
}
