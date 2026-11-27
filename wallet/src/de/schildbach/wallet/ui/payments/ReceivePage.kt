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

package de.schildbach.wallet.ui.payments

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.schildbach.wallet_test.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.DashWalletTheme
import org.dash.wallet.common.ui.components.LocalDashColors
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.Size
import org.dash.wallet.common.ui.components.Style
import org.dash.wallet.common.ui.segmented_picker.SegmentedOption
import org.dash.wallet.common.ui.segmented_picker.SegmentedPicker
import org.dash.wallet.common.ui.segmented_picker.SegmentedPickerStyle
import org.dash.wallet.common.util.Qr

/**
 * The Receive tab of the Advanced payments screen (Figma 7062:49303, block 7062:49310):
 * one card with the balance picker, the QR code, the username and address rows, and the
 * Share / Specify amount buttons.
 */
@Composable
fun ReceivePage(
    uiState: ReceiveUIState,
    onSelectTab: (ReceiveBalanceTab) -> Unit,
    onCopyUsername: (String) -> Unit,
    onCopyAddress: (String) -> Unit,
    onShareAddress: (String) -> Unit,
    onSpecifyAmount: (tab: ReceiveBalanceTab, address: String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalDashColors.current
    // The screen's bottom navigation (ShowNavBar) is drawn over the page, so the scroll ends a
    // bar's height lower: the last buttons scroll clear of it and keep the design's 20dp gap.
    val navBarClearance = dimensionResource(R.dimen.bottom_nav_bar_height)
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 20.dp + navBarClearance)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Figma shadows/xs: 0 5 20 #B8C1CC @ 10%.
                .shadow(
                    elevation = 20.dp,
                    shape = RoundedCornerShape(20.dp),
                    ambientColor = Color(0x1AB8C1CC),
                    spotColor = Color(0x1AB8C1CC)
                )
                .background(colors.backgroundSecondary, RoundedCornerShape(20.dp))
        ) {
            if (uiState.tabs.size > 1) {
                BalancePicker(uiState.tabs, uiState.selectedTab, onSelectTab)
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(40.dp)
            ) {
                QrCode(uiState)

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    val username = uiState.username
                    if (uiState.showUsername && username != null) {
                        CopyRow(
                            label = stringResource(org.dash.wallet.common.R.string.username),
                            value = username,
                            copyDescription = stringResource(R.string.receive_copy_username),
                            onCopy = { onCopyUsername(username) }
                        )
                    }
                    val address = uiState.address
                    if (address != null) {
                        CopyRow(
                            label = stringResource(addressLabelFor(uiState.selectedTab)),
                            value = address,
                            copyDescription = stringResource(R.string.receive_copy_address),
                            onCopy = { onCopyAddress(address) }
                        )
                    }
                }
            }

            val address = uiState.address
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                DashButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.receive_share_address),
                    style = Style.TintedGray,
                    size = Size.Medium,
                    isEnabled = address != null,
                    onClick = { address?.let(onShareAddress) }
                )
                DashButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.receive_specify_amount),
                    style = Style.TintedGray,
                    size = Size.Medium,
                    isEnabled = uiState.specifyAmountEnabled,
                    // The tab and address on show, so the request is for what the user saw.
                    onClick = { onSpecifyAmount(uiState.selectedTab, address) }
                )
            }
        }
    }
}

/** The label of [tab]'s address or ID, here and in the Specify amount result. */
internal fun addressLabelFor(tab: ReceiveBalanceTab): Int = when (tab) {
    ReceiveBalanceTab.CORE -> R.string.receive_core_address_label
    ReceiveBalanceTab.SHIELD -> R.string.receive_shielded_address_label
    ReceiveBalanceTab.PLATFORM -> R.string.receive_platform_address_label
    ReceiveBalanceTab.IDENTITY -> R.string.receive_identity_id_label
}

private fun tabTitleFor(tab: ReceiveBalanceTab): Int = when (tab) {
    ReceiveBalanceTab.CORE -> R.string.receive_balance_core
    ReceiveBalanceTab.SHIELD -> R.string.receive_balance_shield
    ReceiveBalanceTab.PLATFORM -> R.string.receive_balance_platform
    ReceiveBalanceTab.IDENTITY -> R.string.receive_balance_identity
}

/**
 * Figma 7062:49313: text-only segments sized to their labels, the selected one on a gray pill.
 * The row spans the card's full width: the 20dp side inset is padding inside the scroll, and the
 * ends fade out so segments scroll to the card edge.
 */
@Composable
private fun BalancePicker(
    tabs: List<ReceiveBalanceTab>,
    selectedTab: ReceiveBalanceTab,
    onSelectTab: (ReceiveBalanceTab) -> Unit
) {
    val colors = LocalDashColors.current
    // Resolved as stringResource does (it reads LocalConfiguration so a locale change
    // recomposes), but only when the tabs or the configuration change.
    val configuration = LocalConfiguration.current
    val resources = LocalContext.current.resources
    val options = remember(tabs, configuration, resources) {
        tabs.map { SegmentedOption(resources.getString(tabTitleFor(it))) }
    }
    SegmentedPicker(
        options = options,
        modifier = Modifier
            .padding(vertical = 20.dp)
            .fillMaxWidth()
            .height(40.dp),
        selectedIndex = tabs.indexOf(selectedTab).coerceAtLeast(0),
        style = SegmentedPickerStyle(
            backgroundColor = Color.Transparent,
            // segment/bg: Gray300 at 15%.
            thumbColor = colors.gray300.copy(alpha = 0.15f),
            cornerRadius = 12f,
            thumbCornerRadius = 12f,
            containerPadding = 0f,
            shadowElevation = 0,
            showDividers = false,
            textStyle = MyTheme.Typography.BodyMediumSemibold,
            optionPaddingHorizontal = 20f,
            fitOptionsToContent = true,
            unselectedContentAlpha = 0.35f,
            contentPaddingHorizontal = 20f,
            // Figma 7062:49310 mask: alpha 0 at 0%, opaque from 4.792% to 95.297%, 0 at 100%.
            edgeFadeFraction = 0.04792f
        )
    ) { _, index ->
        tabs.getOrNull(index)?.let(onSelectTab)
    }
}

/** Figma 7062:49318: a 200dp code on a white 10dp-padded wrap, the Dash logo in the centre. */
@Composable
private fun QrCode(uiState: ReceiveUIState) {
    val colors = LocalDashColors.current
    val payload = uiState.qrPayload
    Box(
        modifier = Modifier
            .size(220.dp)
            .clip(RoundedCornerShape(10.dp))
            // Always dark modules on white, whatever the theme: many scanners reject an
            // inverted code.
            .background(if (payload != null) Color.White else colors.backgroundSecondary),
        contentAlignment = Alignment.Center
    ) {
        val bitmap = rememberQrBitmap(payload)
        val imageBitmap = remember(bitmap) { bitmap?.asImageBitmap() }
        when {
            imageBitmap != null -> {
                val description = stringResource(R.string.receive_qr_code_description)
                Image(
                    bitmap = imageBitmap,
                    contentDescription = description,
                    colorFilter = ColorFilter.tint(Color.Black, BlendMode.SrcIn),
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.size(200.dp)
                )
                Image(
                    painter = painterResource(org.dash.wallet.common.R.drawable.ic_dash_d_circle),
                    contentDescription = null,
                    modifier = Modifier.size(44.dp)
                )
            }
            // Not read yet, or reading: only a read that failed says so.
            !uiState.unavailable -> CircularProgressIndicator(
                color = colors.dashBlue,
                strokeWidth = 2.dp,
                modifier = Modifier.size(32.dp)
            )
            else -> Text(
                text = stringResource(R.string.receive_address_unavailable),
                style = MyTheme.Typography.BodyMedium,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(20.dp)
            )
        }
    }
}

/**
 * The QR bitmap for [payload], encoded on [Dispatchers.Default]; null until it is ready. The
 * state is keyed on [payload], so a new payload never shows the previous one's code.
 */
@Composable
private fun rememberQrBitmap(payload: String?): Bitmap? {
    val bitmap = remember(payload) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(bitmap) {
        if (payload != null) {
            bitmap.value = withContext(Dispatchers.Default) { Qr.qrBitmap(payload) }
        }
    }
    return bitmap.value
}

/** Figma 7062:49320 / 7062:49391: label over value, a gray tinted copy button on the right. */
@Composable
private fun CopyRow(label: String, value: String, copyDescription: String, onCopy: () -> Unit) {
    val colors = LocalDashColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(text = label, style = MyTheme.Typography.Footnote, color = colors.textSecondary)
            Text(text = value, style = MyTheme.Typography.Callout, color = colors.textPrimary)
        }
        CopyButton(description = copyDescription, onClick = onCopy)
    }
}

/** `btn-m tinted-gray` with only an icon: the same tokens as [DashButton]'s TintedGray/Medium. */
@Composable
private fun CopyButton(description: String, onClick: () -> Unit) {
    val colors = LocalDashColors.current
    Box(
        modifier = Modifier
            .size(width = 49.dp, height = 40.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.gray.copy(alpha = 0.10f))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(org.dash.wallet.common.R.drawable.ic_copy),
            contentDescription = null,
            tint = colors.textPrimary,
            // Figma: a 15dp glyph in a 20dp slot with a 5% outset.
            modifier = Modifier.size(16.5.dp)
        )
    }
}

@Preview(showBackground = true, widthDp = 420, heightDp = 760)
@Composable
private fun ReceivePagePreview() {
    DashWalletTheme {
        Box(modifier = Modifier.background(LocalDashColors.current.backgroundPrimary)) {
            ReceivePage(
                uiState = ReceiveUIState(
                    tabs = ReceiveBalanceTab.entries,
                    address = "yZ3XpruChNgctwFvCzQ5WpFg7RpytQVwkA",
                    username = "johndoe"
                ),
                onSelectTab = {},
                onCopyUsername = {},
                onCopyAddress = {},
                onShareAddress = {},
                onSpecifyAmount = { _, _ -> }
            )
        }
    }
}
