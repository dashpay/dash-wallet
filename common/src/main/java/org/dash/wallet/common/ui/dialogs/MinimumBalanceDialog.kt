/*
 * Copyright 2022 Dash Core Group.
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

package org.dash.wallet.common.ui.dialogs

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import dagger.hilt.android.AndroidEntryPoint
import org.dash.wallet.common.R
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import javax.inject.Inject

@AndroidEntryPoint
class MinimumBalanceDialog : AdaptiveDialog(R.layout.dialog_adaptive) {
    @Inject
    lateinit var analytics: AnalyticsService

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        arguments = bundleOf(
            ICON_RES_ARG to R.drawable.ic_info_blue,
            TITLE_ARG to getString(R.string.empty_wallet_warning_title),
            MESSAGE_ARG to getString(R.string.empty_wallet_crowdnode_warning),
            NEG_BUTTON_ARG to getString(R.string.button_close),
            POS_BUTTON_ARG to getString(R.string.button_continue)
        )

        super.onViewCreated(view, savedInstanceState)
    }

    override fun onPositiveAction() {
        analytics.logEvent(AnalyticsConstants.CrowdNode.LOW_BALANCE_PROCEED, mapOf())
        super.onPositiveAction()
    }

    override fun onNegativeAction() {
        analytics.logEvent(AnalyticsConstants.CrowdNode.LOW_BALANCE_CANCEL, mapOf())
        super.onNegativeAction()
    }
}
