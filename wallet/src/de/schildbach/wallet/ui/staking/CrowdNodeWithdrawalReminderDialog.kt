/*
 * Copyright (c) 2025 Dash Core Group
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui.staking

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.schildbach.wallet.ui.compose_views.ComposeBottomSheet
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.components.ButtonGroupOrientation
import org.dash.wallet.common.ui.components.FeatureTopText
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.SheetButton
import org.dash.wallet.common.ui.components.SheetButtonGroup
import org.dash.wallet.common.ui.components.Style

/**
 * Creates a dismissible bottom sheet shown on MainActivity after sync when the user still has a
 * balance on CrowdNode, nudging them to withdraw it.
 *
 * Figma Node ID: 2464:18269 (Sheet CrowdNode. Withdraw funds)
 *
 * @param onWithdraw invoked when the user taps "Withdraw funds" (before the sheet dismisses)
 * @return ComposeBottomSheet instance ready to be shown
 */
fun createCrowdNodeWithdrawalReminderDialog(onWithdraw: () -> Unit): ComposeBottomSheet {
    return ComposeBottomSheet(
        backgroundStyle = R.style.SecondaryBackground,
        forceExpand = false,
        content = { dialog ->
            CrowdNodeWithdrawalReminderContent(
                onWithdraw = {
                    onWithdraw()
                    dialog.dismiss()
                },
                onClose = { dialog.dismiss() }
            )
        }
    )
}

@Composable
private fun CrowdNodeWithdrawalReminderContent(
    onWithdraw: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 60.dp), // Space for drag indicator and close button
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Composite icon: CrowdNode logo on a light circle with a warning-triangle badge
        Box(
            modifier = Modifier.padding(top = 20.dp, bottom = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .background(MyTheme.Colors.backgroundPrimary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_crowdnode_logo),
                    contentDescription = null,
                    modifier = Modifier.size(44.dp)
                )
            }
            Image(
                painter = painterResource(R.drawable.ic_warning_triangle),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = (-12).dp, y = (-8).dp)
                    .size(22.dp)
            )
        }

        FeatureTopText(
            heading = stringResource(R.string.crowdnode_withdrawal_reminder_title),
            text = stringResource(R.string.crowdnode_withdrawal_reminder_message),
            showText = true,
            showButton = false,
            modifier = Modifier.padding(top = 20.dp, bottom = 32.dp)
        )

        SheetButtonGroup(
            primaryButton = SheetButton(
                text = stringResource(R.string.crowdnode_withdraw_funds),
                style = Style.FilledBlue,
                onClick = onWithdraw
            ),
            secondaryButton = SheetButton(
                text = stringResource(R.string.button_close),
                style = Style.TintedGray,
                onClick = onClose
            ),
            orientation = ButtonGroupOrientation.Vertical,
            spacing = 16.dp
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun CrowdNodeWithdrawalReminderContentPreview() {
    CrowdNodeWithdrawalReminderContent(onWithdraw = {}, onClose = {})
}
