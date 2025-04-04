package de.schildbach.wallet.ui.util

import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import de.schildbach.wallet.ui.compose_views.ComposeBottomSheet
import de.schildbach.wallet.ui.transactions.BlockExplorerSelectionView
import de.schildbach.wallet_test.R

fun FragmentActivity.showBlockExplorerSelectionSheet(appendPath: String) {
    ComposeBottomSheet(R.style.PrimaryBackground) { dialog ->
        BlockExplorerSelectionView { explorer ->
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.withAppendedPath(
                        resources.getStringArray(R.array.preferences_block_explorer_values)[explorer.index].toUri(),
                        appendPath
                    )
                )
            )
            dialog.dismiss()
        }
    }.show(this)
} 