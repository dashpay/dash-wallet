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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.schildbach.wallet_test.R
import de.schildbach.wallet.ui.compose_views.ComposeBottomSheet
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.Size
import org.dash.wallet.common.ui.components.Style

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

        // Title
        Text(
            text = if (recover) stringResource(R.string.forgot_pin_setup_new_pin_title) else stringResource(R.string.forgot_pin_title),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF191C1F),
            textAlign = TextAlign.Center,
            lineHeight = 32.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 60.dp, vertical = 20.dp)
        )

        // Steps list
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Step 1
            RecoveryStep(
                stepNumber = 1,
                stepText = stringResource(R.string.forgot_pin_instruction_1)
            )

            // Step 2
            RecoveryStep(
                stepNumber = 2,
                stepText = stringResource(R.string.forgot_pin_instruction_2)
            )

            // Step 3
            RecoveryStep(
                stepNumber = 3,
                stepText = stringResource(R.string.forgot_pin_instruction_3)
            )
        }

        Spacer(modifier = Modifier.height(38.dp))

        // Button section
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Recover wallet button
            DashButton(
                text = stringResource(R.string.forgot_pin_recover),
                style = Style.FilledBlue,
                size = Size.Large,
                onClick = onRecoverWalletClick
            )
        }

        // Home indicator spacing
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun RecoveryStep(
    stepNumber: Int,
    stepText: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Numbered badge
        Box(
            modifier = Modifier
                .size(40.dp)
                .padding(top = 10.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(
                        color = Color(0xFF008DE4),
                        shape = RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stepNumber.toString(),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Step text
        Text(
            text = stepText,
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
            color = Color(0xFF191C1F),
            lineHeight = 20.sp,
            modifier = Modifier
                .weight(1f)
                .padding(top = 10.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ForgotPinContentPreview() {
    ForgotPinContent(
        true,
        onRecoverWalletClick = { }
    )
}