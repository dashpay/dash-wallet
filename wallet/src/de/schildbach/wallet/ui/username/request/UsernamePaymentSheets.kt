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
package de.schildbach.wallet.ui.username.request

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
 * Compose content of the two shielded-funds payment sheets shown at the
 * create-username decision point (Figma flow canvas 555:811). Both sheets
 * are hosted in [UsernamePaymentDialogFragment].
 *
 * Design values (both sheets share the chrome of the iOS frames, converted
 * to Android idiom): grabber + 64dp nav bar with a trailing 34dp circled
 * close, 28sp bold title over a 14sp secondary description at 40dp side
 * padding, buttons at 60dp side padding.
 */

/** Unselected option-card stroke — Figma `select/stroke-default` #B0B6BC4D. */
private val SelectStrokeDefault = Color(0x4DB0B6BC)

/**
 * "Select your payment option" sheet (Figma 1856:1805, states 1855:11870
 * unselected / 1856:1476 selected): Shielded balance vs Dash balance, with
 * Continue disabled until an option is picked.
 */
@Composable
fun SelectPaymentOptionSheet(
    selectedSource: UsernamePaymentSource?,
    onSelect: (UsernamePaymentSource) -> Unit,
    onContinue: () -> Unit,
    onClose: () -> Unit
) {
    // No opaque root background: the hosting OffsetDialogFragment draws the
    // rounded sheet background (previews approximate it via showBackground).
    Column(modifier = Modifier.fillMaxWidth()) {
        SheetChrome(onClose = onClose)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            SheetIntro(
                title = stringResource(R.string.username_payment_select_title),
                description = stringResource(R.string.username_payment_select_message)
            )

            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                PaymentOptionCard(
                    name = stringResource(R.string.username_payment_option_shielded),
                    description = stringResource(R.string.username_payment_option_shielded_hint),
                    selected = selectedSource == UsernamePaymentSource.SHIELDED_BALANCE,
                    onClick = { onSelect(UsernamePaymentSource.SHIELDED_BALANCE) }
                )
                PaymentOptionCard(
                    name = stringResource(R.string.username_payment_option_dash),
                    description = stringResource(R.string.username_payment_option_dash_hint),
                    selected = selectedSource == UsernamePaymentSource.DASH_BALANCE,
                    onClick = { onSelect(UsernamePaymentSource.DASH_BALANCE) }
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 60.dp, vertical = 20.dp)
        ) {
            DashButton(
                text = stringResource(org.dash.wallet.common.R.string.button_continue),
                style = Style.Filled,
                size = Size.Large,
                isEnabled = selectedSource != null,
                onClick = onContinue
            )
        }
    }
}

/**
 * "Make your username private" sheet (Figma 1856:1519), shown when there
 * are no usable shielded funds: privacy-tip card plus "Shield your funds
 * first" (primary) and "Continue without privacy" (tinted).
 */
@Composable
fun MakeUsernamePrivateSheet(
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
                .padding(horizontal = 40.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            SheetIntro(
                title = stringResource(R.string.username_payment_private_title),
                description = stringResource(R.string.username_payment_select_message)
            )

            // Privacy-tip card (Figma 1856:1784): BlueAlpha5 panel, 20dp
            // radius, 16dp padding, 20dp info glyph + title/message column.
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
                        text = stringResource(R.string.username_payment_privacy_tip_message),
                        style = MyTheme.Typography.BodyMedium,
                        color = MyTheme.Colors.textSecondary
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 60.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DashButton(
                text = stringResource(R.string.username_payment_shield_first),
                style = Style.Filled,
                size = Size.Large,
                onClick = onShieldFirst
            )
            DashButton(
                text = stringResource(R.string.username_payment_continue_without_privacy),
                style = Style.TintedBlue,
                size = Size.Large,
                onClick = onContinueWithoutPrivacy
            )
        }
    }
}

/** Grabber + nav bar with the trailing circled close (both sheets' `controls` frame). */
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

/** Title (28sp bold) + description (14sp secondary) block shared by both sheets. */
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

/**
 * One selectable payment option (Figma `simpleSelect`): 16dp-radius card,
 * 1.5dp stroke, 20/12dp padding; selected state fills BlueAlpha5 with a
 * dash-blue stroke.
 */
@Composable
private fun PaymentOptionCard(
    name: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) MyTheme.Colors.dashBlue5 else Color.Transparent, shape)
            .border(1.5.dp, if (selected) MyTheme.Colors.dashBlue else SelectStrokeDefault, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        Text(
            text = name,
            style = MyTheme.Typography.BodyMediumMedium,
            color = MyTheme.Colors.textPrimary
        )
        Text(
            text = description,
            style = MyTheme.Typography.BodySmall,
            color = MyTheme.Colors.textSecondary
        )
    }
}

// ── Previews ────────────────────────────────────────────────────────────────

@Preview(showBackground = true, widthDp = 393, name = "Select payment option — none selected")
@Composable
private fun SelectPaymentOptionPreview() {
    var selected by remember { mutableStateOf<UsernamePaymentSource?>(null) }
    SelectPaymentOptionSheet(
        selectedSource = selected,
        onSelect = { selected = it },
        onContinue = {},
        onClose = {}
    )
}

@Preview(showBackground = true, widthDp = 393, name = "Select payment option — shielded selected")
@Composable
private fun SelectPaymentOptionSelectedPreview() {
    SelectPaymentOptionSheet(
        selectedSource = UsernamePaymentSource.SHIELDED_BALANCE,
        onSelect = {},
        onContinue = {},
        onClose = {}
    )
}

@Preview(showBackground = true, widthDp = 393, name = "Make your username private")
@Composable
private fun MakeUsernamePrivatePreview() {
    MakeUsernamePrivateSheet(
        onShieldFirst = {},
        onContinueWithoutPrivacy = {},
        onClose = {}
    )
}
