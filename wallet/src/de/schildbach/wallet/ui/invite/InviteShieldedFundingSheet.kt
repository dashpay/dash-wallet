/*
 * Copyright 2026 Dash Core Group.
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
package de.schildbach.wallet.ui.invite

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.Grabber
import org.dash.wallet.common.ui.components.MyImages
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.Size
import org.dash.wallet.common.ui.components.Style
import org.dash.wallet.common.ui.components.TopNavBase

/**
 * Compose content of the create-invitation shielded-funding decision sheet
 * (Figma 25163:53221), hosted in [InviteShieldedFundingDialogFragment].
 *
 * It mirrors the create-username flow's "Make your username private" sheet
 * (Figma 1856:1519) — same chrome, privacy-tip card and button pair
 * ("Shield your funds first" primary + "Continue without privacy" tinted) —
 * adapted to invitations, and surfaces BOTH the Private (shielded) and the
 * Standard (non-private) cost of each username kind so the user can compare
 * before deciding (Fix G1). The amounts are the ones actually WITHDRAWN
 * (consistent with the fee/confirm screens): Private 0.1 / 0.3, Standard the
 * L1 fees 0.03 / 0.25 — all resolved by the ViewModel, never hardcoded here.
 *
 * The four cost params are plain DASH strings (e.g. "0.1" / "0.3" / "0.03" /
 * "0.25"); [canShieldMinimum] disables the shield-first button when the
 * wallet holds less than the shield-guidance minimum.
 */
@Composable
fun InviteShieldedFundingSheet(
    nonContestedPrivateCost: String,
    contestedPrivateCost: String,
    nonContestedStandardCost: String,
    contestedStandardCost: String,
    canShieldMinimum: Boolean,
    canCreatePrivateInvite: Boolean,
    primaryLoading: Boolean,
    onCreatePrivateInvite: () -> Unit,
    onShieldFirst: () -> Unit,
    onContinueWithoutPrivacy: () -> Unit,
    onClose: () -> Unit
) {
    // No opaque root background: the hosting OffsetDialogFragment draws the
    // rounded sheet background (previews approximate it via showBackground).
    Column(modifier = Modifier.fillMaxWidth()) {
        SheetChrome(onClose = onClose)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Wider content with more breathing room (Fix G1): narrower
                // side padding widens every component, larger vertical gap.
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            SheetIntro(
                title = stringResource(R.string.invite_payment_private_title),
                description = stringResource(R.string.invite_payment_private_message)
            )

            // Cost comparison table (Fix G1): two columns — Private (shielded)
            // vs Standard (non-private) — over the Non-contested and Contested
            // rows, showing the amount actually withdrawn in each case.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MyTheme.Colors.backgroundPrimary, RoundedCornerShape(20.dp))
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CostRow(
                    label = "",
                    privateText = stringResource(R.string.invite_payment_column_private),
                    standardText = stringResource(R.string.invite_payment_column_standard),
                    header = true
                )
                CostRow(
                    label = stringResource(R.string.invitation_fee_noncontested),
                    privateText = stringResource(R.string.invite_payment_cost_amount, nonContestedPrivateCost),
                    standardText = stringResource(R.string.invite_payment_cost_amount, nonContestedStandardCost)
                )
                CostRow(
                    label = stringResource(R.string.invitation_fee_contested),
                    privateText = stringResource(R.string.invite_payment_cost_amount, contestedPrivateCost),
                    standardText = stringResource(R.string.invite_payment_cost_amount, contestedStandardCost)
                )
            }

            // Privacy-tip card (mirrors the username sheet's 1856:1784):
            // BlueAlpha5 panel, 20dp radius, 16dp padding, 20dp info glyph +
            // title/message column.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MyTheme.Colors.dashBlue5, RoundedCornerShape(20.dp))
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Image(
                    painter = painterResource(org.dash.wallet.common.R.drawable.ic_info_blue),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.shielded_privacy_tip_title),
                        style = MyTheme.Typography.BodyMediumMedium,
                        color = MyTheme.Colors.textPrimary
                    )
                    Text(
                        text = stringResource(R.string.invite_payment_privacy_tip_message),
                        style = MyTheme.Typography.BodyMedium,
                        color = MyTheme.Colors.textSecondary
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Wider buttons with a touch more spacing (Fix G1).
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Primary action. While the shielded balance/sync is still
            // resolving the private-invite decision cannot be trusted, so show
            // a single disabled "Preparing shielded balance…" primary instead
            // of first rendering "Shield your funds first" and then flipping to
            // "Create a private invitation" once the pool reaches READY (Fix B —
            // button-label flicker). Only once resolved do we render the decided
            // SHIELD_FIRST / CREATE_PRIVATE button.
            if (primaryLoading) {
                DashButton(
                    text = stringResource(R.string.username_preparing_shielded_balance),
                    style = Style.Filled,
                    size = Size.Large,
                    isEnabled = false,
                    onClick = {}
                )
            } else if (canCreatePrivateInvite) {
                DashButton(
                    text = stringResource(R.string.invite_payment_create_private),
                    style = Style.Filled,
                    size = Size.Large,
                    onClick = onCreatePrivateInvite
                )
            } else {
                DashButton(
                    text = stringResource(R.string.username_payment_shield_first),
                    style = Style.Filled,
                    size = Size.Large,
                    isEnabled = canShieldMinimum,
                    onClick = onShieldFirst
                )
            }
            DashButton(
                text = stringResource(R.string.username_payment_continue_without_privacy),
                style = Style.TintedBlue,
                size = Size.Large,
                onClick = onContinueWithoutPrivacy
            )
        }
    }
}

/**
 * One cost-table row: kind [label] (leading) plus the [privateText] and
 * [standardText] columns (trailing, right-aligned). [header] renders the
 * "Private" / "Standard" column titles in the secondary style; data rows put
 * the amounts in the primary style.
 */
@Composable
private fun CostRow(
    label: String,
    privateText: String,
    standardText: String,
    header: Boolean = false
) {
    val amountStyle = if (header) MyTheme.Typography.BodyMedium else MyTheme.Typography.BodyMediumMedium
    val amountColor = if (header) MyTheme.Colors.textSecondary else MyTheme.Colors.textPrimary
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MyTheme.Typography.BodyMedium,
            color = MyTheme.Colors.textSecondary,
            modifier = Modifier.weight(1.3f)
        )
        Text(
            text = privateText,
            style = amountStyle,
            color = amountColor,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = standardText,
            style = amountStyle,
            color = amountColor,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

/** Grabber + nav bar with the trailing circled close (the sheet's `controls` frame). */
@Composable
private fun SheetChrome(onClose: () -> Unit) {
    Grabber()
    TopNavBase(
        leadingPart = false,
        centralPart = false,
        trailingIcon = MyImages.NavBarClose,
        trailingContentDescription = stringResource(org.dash.wallet.common.R.string.button_close),
        onTrailingClick = onClose
    )
}

/** Title (28sp bold) + description (14sp secondary) block. */
@Composable
private fun SheetIntro(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MyTheme.Typography.HeadlineMediumBold,
            color = MyTheme.Colors.textPrimary,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = description,
            style = MyTheme.Typography.BodyMedium,
            color = MyTheme.Colors.textSecondary,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ── Previews ────────────────────────────────────────────────────────────────

@Preview(showBackground = true, widthDp = 393, name = "Make invitation private")
@Composable
private fun InviteShieldedFundingPreview() {
    InviteShieldedFundingSheet(
        nonContestedPrivateCost = "0.1",
        contestedPrivateCost = "0.3",
        nonContestedStandardCost = "0.03",
        contestedStandardCost = "0.25",
        canShieldMinimum = true,
        canCreatePrivateInvite = false,
        primaryLoading = false,
        onCreatePrivateInvite = {},
        onShieldFirst = {},
        onContinueWithoutPrivacy = {},
        onClose = {}
    )
}

@Preview(showBackground = true, widthDp = 393, name = "Make invitation private — below minimum")
@Composable
private fun InviteShieldedFundingBelowMinimumPreview() {
    InviteShieldedFundingSheet(
        nonContestedPrivateCost = "0.1",
        contestedPrivateCost = "0.3",
        nonContestedStandardCost = "0.03",
        contestedStandardCost = "0.25",
        canShieldMinimum = false,
        canCreatePrivateInvite = false,
        primaryLoading = false,
        onCreatePrivateInvite = {},
        onShieldFirst = {},
        onContinueWithoutPrivacy = {},
        onClose = {}
    )
}

@Preview(showBackground = true, widthDp = 393, name = "Make invitation private — pool can fund")
@Composable
private fun InviteShieldedFundingCreatePrivatePreview() {
    InviteShieldedFundingSheet(
        nonContestedPrivateCost = "0.1",
        contestedPrivateCost = "0.3",
        nonContestedStandardCost = "0.03",
        contestedStandardCost = "0.25",
        canShieldMinimum = true,
        canCreatePrivateInvite = true,
        primaryLoading = false,
        onCreatePrivateInvite = {},
        onShieldFirst = {},
        onContinueWithoutPrivacy = {},
        onClose = {}
    )
}
