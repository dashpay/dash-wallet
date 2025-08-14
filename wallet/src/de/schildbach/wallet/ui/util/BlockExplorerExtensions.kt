/*
 * Copyright (c) 2025. Dash Core Group.
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

package de.schildbach.wallet.ui.util

import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import de.schildbach.wallet.ui.compose_views.ComposeBottomSheet
import de.schildbach.wallet.ui.transactions.BlockExplorer
import de.schildbach.wallet.ui.transactions.BlockExplorerSelectionView
import de.schildbach.wallet_test.R
import org.dash.wallet.common.services.analytics.AnalyticsService

fun FragmentActivity.showBlockExplorerSelectionSheet(analytics: AnalyticsService, appendPath: String) {
    ComposeBottomSheet(R.style.PrimaryBackground) { dialog ->
        BlockExplorerSelectionView(analytics) { explorer ->
            viewOnBlockExplorer(explorer, appendPath)
            dialog.dismiss()
        }
    }.show(this)
}

fun FragmentActivity.viewOnBlockExplorer(
    explorer: BlockExplorer,
    appendPath: String
) {
    val explorerUrl = resources.getStringArray(R.array.preferences_block_explorer_values)[explorer.index]
    val baseUri = explorerUrl.toUri()
    val finalUri = if (explorerUrl.contains("blockchair.com")) {
        Uri.withAppendedPath(baseUri, appendPath).buildUpon()
            .appendQueryParameter("from", "dash")
            .build()
    } else {
        Uri.withAppendedPath(baseUri, appendPath)
    }

    startActivity(Intent(Intent.ACTION_VIEW, finalUri))
}