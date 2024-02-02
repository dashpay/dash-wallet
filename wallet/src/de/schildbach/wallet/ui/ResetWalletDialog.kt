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
import android.content.DialogInterface
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.service.RestartService
import de.schildbach.wallet_test.R
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.ui.BaseAlertDialogBuilder
import org.dash.wallet.common.ui.dismissDialog
import javax.inject.Inject

@AndroidEntryPoint
class ResetWalletDialog : DialogFragment() {
    private lateinit var alertDialog: AlertDialog
    @Inject
    lateinit var analytics: AnalyticsService
    @Inject
    lateinit var restartService: RestartService

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        alertDialog = BaseAlertDialogBuilder(requireContext())
            .apply {
                title = getString(R.string.wallet_lock_reset_wallet_title)
                message = getString(R.string.wallet_lock_reset_wallet_message)
                negativeText = getString(R.string.wallet_lock_reset_wallet_title)
                negativeAction = {
                    (activity as? AbstractBindServiceActivity)?.unbindServiceServiceConnection()
                    // 1. wipe the wallet
                    // 2. start OnboardingActivity
                    // 3. close the backstack (Home->More->Security)
                    WalletApplication.getInstance().triggerWipe()
                    restartService.performRestart(requireActivity(), true)
                }
                positiveText = getString(android.R.string.no)
                cancelable = false
                isCancelableOnTouchOutside = false
            }.buildAlertDialog()

        return alertDialog
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        alertDialog.dismissDialog()
    }

    companion object {
        @JvmStatic
        fun newInstance(analyticsService: AnalyticsService): ResetWalletDialog {
            return ResetWalletDialog().apply { analytics = analyticsService }
        }
    }
}
