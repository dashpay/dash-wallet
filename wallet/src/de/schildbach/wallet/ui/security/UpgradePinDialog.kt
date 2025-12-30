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

package de.schildbach.wallet.ui.security

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.schildbach.wallet_test.R
import de.schildbach.wallet.ui.compose_views.ComposeBottomSheet
import org.dash.wallet.common.ui.components.FeatureTopText
import org.dash.wallet.common.ui.components.SheetButton
import org.dash.wallet.common.ui.components.SheetButtonGroup
import org.dash.wallet.common.ui.components.Style

/**
 * Factory function that creates a PIN upgrade dialog
 * Shows when user needs to upgrade their PIN to the new security system
 */
fun createUpgradePinDialog(
    onContinue: () -> Unit = {}
): ComposeBottomSheet {
    val bottomSheet = ComposeBottomSheet(
        backgroundStyle = R.style.SecondaryBackground,
        forceExpand = false,
        content = { dialog ->
            UpgradePinContent(
                onContinueClick = {
                    onContinue()
                    dialog.dismiss()
                }
            )
        }
    )
    bottomSheet.isCancelable = false
    return bottomSheet
}

@Composable
private fun UpgradePinContent(
    onContinueClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 60.dp) // Space for drag indicator and close button
    ) {
        // Icon/Illustration
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp, bottom = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.ic_padlock),
                contentDescription = null,
                modifier = Modifier
                    .width(63.dp)
                    .height(100.dp)
            )
        }

        FeatureTopText(
            heading = stringResource(R.string.upgrade_pin_title),
            modifier = Modifier
            //    .fillMaxWidth(),
                .padding(horizontal = 20.dp, vertical = 20.dp),
            // horizontalAlignment = Alignment.CenterHorizontally,
            text = stringResource(R.string.upgrade_pin_description),
            showText = true,
            showButton = false
        )

        Spacer(modifier = Modifier.height(28.dp))

        // Buttons section
        SheetButtonGroup(
            primaryButton = SheetButton(
                text = stringResource(R.string.upgrade_pin_continue),
                style = Style.FilledBlue,
                onClick = onContinueClick
            )
        )

        // Home indicator spacing
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Preview(showBackground = true)
@Composable
private fun UpgradePinContentPreview() {
    UpgradePinContent(
        onContinueClick = { }
    )
}