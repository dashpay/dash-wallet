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
import androidx.core.os.bundleOf
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet_test.R
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.FirebaseAnalyticsServiceImpl
import org.dash.wallet.common.ui.BaseAlertDialogBuilder
import org.dash.wallet.common.ui.BaseDialogFragment

class ResetWalletDialog : BaseDialogFragment() {
    private val analytics = FirebaseAnalyticsServiceImpl.getInstance()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        alertDialog = BaseAlertDialogBuilder(requireContext())
            .apply {
                title = getString(R.string.wallet_lock_reset_wallet_title)
                message = getString(R.string.wallet_lock_reset_wallet_message)
                negativeText = getString(R.string.wallet_lock_reset_wallet_title)
                negativeAction = {
                    analytics.logEvent(AnalyticsConstants.Security.RESET_WALLET, bundleOf())
                    (activity as? AbstractBindServiceActivity)?.unbindServiceServiceConnection()
                    WalletApplication.getInstance().triggerWipe(context)
                }
                positiveText = getString(android.R.string.no)
                isDialogCancelable = false
                isCancelableOnTouchOutside = false
            }.buildAlertDialog()
        return super.onCreateDialog(savedInstanceState)
    }

    companion object {
        @JvmStatic
        fun newInstance(): ResetWalletDialog {
            return ResetWalletDialog()
        }
    }
}
