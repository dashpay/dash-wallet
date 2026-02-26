/*
 * Copyright 2025 Dash Core Group.
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

package de.schildbach.wallet.ui.compose_views

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.components.FeatureTopText
import org.dash.wallet.common.ui.components.MyTheme

/**
 * Creates a bottom sheet dialog that explains how wallet creation date speeds up sync.
 *
 * Figma Node ID: 2878:41574 (Sheet)
 *
 * @return ComposeBottomSheet instance ready to be shown
 */
fun createWalletCreationDateInfoDialog(): ComposeBottomSheet {
    return ComposeBottomSheet(
        backgroundStyle = R.style.SecondaryBackground,
        forceExpand = false,
        content = { dialog ->
            WalletCreationDateInfoContent()
        }
    )
}

@Composable
private fun WalletCreationDateInfoContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            //.background(MyTheme.Colors.backgroundSecondary)
            .padding(top = 60.dp) // Space for drag indicator and close button
    ) {
        // Content wrapper with padding
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp, vertical = 20.dp)
        ) {
            FeatureTopText(
                heading = stringResource(R.string.wallet_creation_date_info_title),
                text = stringResource(R.string.wallet_creation_date_info_description),
                showText = true,
                showButton = false
            )
        }

        // Bottom spacing for home indicator
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Preview(showBackground = true)
@Composable
private fun WalletCreationDateInfoContentPreview() {
    WalletCreationDateInfoContent()
}