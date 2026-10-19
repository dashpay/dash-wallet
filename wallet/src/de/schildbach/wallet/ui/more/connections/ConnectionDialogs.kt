/* Copyright 2026 Dash Core Group. SPDX-License-Identifier: GPL-3.0-or-later */
package de.schildbach.wallet.ui.more.connections

import android.content.Context
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog

internal fun connectionCallbackDialog(context: Context, host: String): AdaptiveDialog = AdaptiveDialog.create(
    null,
    context.getString(R.string.dash_connect_return_title, host),
    context.getString(R.string.dash_connect_return_warning),
    context.getString(android.R.string.cancel),
    context.getString(R.string.dash_connect_return_open)
)

internal fun connectionDetailsDialog(context: Context, name: String, details: String): AdaptiveDialog = AdaptiveDialog.create(
    null,
    name,
    details,
    context.getString(R.string.button_close),
    context.getString(R.string.dash_connect_revoke)
).apply {
    requireArguments().putInt(AdaptiveDialog.POS_BUTTON_COLOR_ARG, R.style.PrimaryButtonTheme_Large_Red)
}
