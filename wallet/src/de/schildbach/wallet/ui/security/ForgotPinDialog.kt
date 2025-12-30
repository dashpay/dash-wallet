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
import org.dash.wallet.common.ui.components.FeatureItem
import org.dash.wallet.common.ui.components.FeatureList
import org.dash.wallet.common.ui.components.FeatureTopText
import org.dash.wallet.common.ui.components.SheetButton
import org.dash.wallet.common.ui.components.SheetButtonGroup
import org.dash.wallet.common.ui.components.Style
import java.text.NumberFormat

/**
 * Factory function that creates a Forgot PIN recovery dialog
 * Shows recovery steps when user forgets their PIN
 */
fun createForgotPinDialog(
    recover: Boolean,
    onRecoverWallet: () -> Unit = {}
): ComposeBottomSheet {
    return ComposeBottomSheet(
        backgroundStyle = R.style.SecondaryBackground,
        forceExpand = false,
        content = { dialog ->
            ForgotPinContent(
                recover,
                onRecoverWalletClick = {
                    onRecoverWallet()
                    dialog.dismiss()
                }
            )
        }
    )
}

@Composable
private fun ForgotPinContent(
    recover: Boolean,
    onRecoverWalletClick: () -> Unit
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
            if (recover) stringResource(R.string.forgot_pin_setup_new_pin_title) else stringResource(R.string.forgot_pin_title),
        )
        Spacer(modifier = Modifier.height(28.dp))
        val numberFormat = NumberFormat.getIntegerInstance()
        FeatureList(
            listOf(
                FeatureItem(
                   heading = stringResource(R.string.forgot_pin_instruction_1),
                    number = numberFormat.format(1)
                ),
                FeatureItem(
                    heading = stringResource(R.string.forgot_pin_instruction_2),
                    number = numberFormat.format(2)
                ),
                FeatureItem(
                    heading = stringResource(R.string.forgot_pin_instruction_3),
                    number = numberFormat.format(3)
                )
            ),
            modifier = Modifier
                .padding(horizontal = 40.dp)
                .fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Button section
        SheetButtonGroup(
            primaryButton = SheetButton(
                text = stringResource(R.string.forgot_pin_recover),
                style = Style.FilledBlue,
                onClick = onRecoverWalletClick
            )
        )

        // Home indicator spacing
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Preview(showBackground = true)
@Composable
private fun RecoverContentPreview() {
    ForgotPinContent(
        true,
        onRecoverWalletClick = { }
    )
}

@Preview(showBackground = true)
@Composable
private fun ForgotPinContentPreview() {
    ForgotPinContent(
        false,
        onRecoverWalletClick = { }
    )
}