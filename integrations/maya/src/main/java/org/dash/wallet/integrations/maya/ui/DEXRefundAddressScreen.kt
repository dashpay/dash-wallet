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

package org.dash.wallet.integrations.maya.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.dash.wallet.common.ui.components.AddressField
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.NavBarBack
import org.dash.wallet.common.ui.components.Size
import org.dash.wallet.common.ui.components.Style
import org.dash.wallet.common.ui.components.Toast
import org.dash.wallet.common.ui.components.ToastImageResource
import org.dash.wallet.common.ui.components.TopIntro
import org.dash.wallet.integrations.maya.R
import org.dash.wallet.common.R as CommonR

@Composable
fun DEXRefundAddressScreen(
    viewModel: DEXRefundAddressViewModel,
    onBackClick: () -> Unit,
    onScanClick: () -> Unit,
    onPasteClick: () -> Unit,
    onContinueClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    DEXRefundAddressScreenContent(
        address = uiState.address,
        currencyCode = uiState.currencyCode,
        continueEnabled = uiState.continueEnabled,
        hasError = uiState.hasError,
        isSubmitting = uiState.isSubmitting,
        orderErrorRes = uiState.orderErrorRes,
        isOnline = uiState.isOnline,
        onAddressChanged = viewModel::onAddressChanged,
        onScanClick = onScanClick,
        onPasteClick = onPasteClick,
        onBackClick = onBackClick,
        onContinueClick = onContinueClick
    )
}

@Composable
private fun DEXRefundAddressScreenContent(
    address: String,
    currencyCode: String,
    continueEnabled: Boolean,
    hasError: Boolean,
    isSubmitting: Boolean,
    @StringRes orderErrorRes: Int?,
    isOnline: Boolean,
    onAddressChanged: (String) -> Unit,
    onScanClick: () -> Unit,
    onPasteClick: () -> Unit,
    onBackClick: () -> Unit,
    onContinueClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MyTheme.Colors.backgroundPrimary)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            NavBarBack(onBackClick = onBackClick)

            // Heading + primary description. Reuses the design-system TopIntro (heading + body text).
            // Sits directly below the nav bar — Figma's content `pt-116px` is just the height of the
            // (overlaid) status bar + nav bar, which NavBarBack already occupies here; TopIntro's
            // built-in padding supplies the remaining 10dp `safe-area/top` and the 20dp gap below.
            TopIntro(
                heading = stringResource(R.string.dex_refund_address_heading),
                text = stringResource(R.string.dex_refund_address_description, currencyCode, currencyCode)
            )

            // Refund address field (design-system AddressField): label + paste/scan input + inline
            // error. The address must validate against the source currency on Continue.
            AddressField(
                value = address,
                onValueChange = onAddressChanged,
                label = stringResource(R.string.dex_refund_address_field_label),
                placeholder = stringResource(R.string.dex_refund_address_placeholder),
                message = if (hasError) {
                    stringResource(CommonR.string.not_valid_address, currencyCode)
                } else {
                    null
                },
                isError = hasError,
                // Disabled while offline (typing / paste / scan) or while the order is being created:
                // there's nothing to validate against without a connection, and the input is locked
                // while the swap is submitted.
                enabled = isOnline && !isSubmitting,
                onScanClick = onScanClick,
                onLongPress = onPasteClick,
                onPasteClick = onPasteClick,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp)
            )

            // SwapKit order-creation error (e.g. no route / expired quote), distinct from the
            // address-format error shown inline in the field above.
            if (orderErrorRes != null) {
                Text(
                    // currencyCode is the format arg; messages without a placeholder ignore it.
                    text = stringResource(orderErrorRes, currencyCode),
                    style = MyTheme.Body2Regular,
                    color = MyTheme.Colors.red,
                    modifier = Modifier.padding(top = 8.dp, start = 20.dp, end = 20.dp)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Pinned bottom Continue button. Shows a spinner while the buy order is created with
            // SwapKit; disabled while offline (nothing to validate against without a connection).
            DashButton(
                text = stringResource(R.string.button_continue),
                style = Style.FilledBlue,
                size = Size.Large,
                isEnabled = continueEnabled && isOnline && !isSubmitting,
                isLoading = isSubmitting,
                onClick = onContinueClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 40.dp, vertical = 16.dp)
            )
        }

        // No-connection toast, pinned at the bottom. Not dismissable — it must stay visible for as
        // long as the device is offline, since the whole screen is disabled until connectivity returns.
        if (!isOnline) {
            Toast(
                text = stringResource(R.string.maya_no_connection_toast),
                imageResource = ToastImageResource.NoInternet.resourceId,
                onActionClick = {},
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 25.dp, vertical = 8.dp)
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 393, heightDp = 760)
@Composable
private fun DEXRefundAddressScreenEmptyPreview() {
    DEXRefundAddressScreenContent(
        address = "",
        currencyCode = "BTC",
        continueEnabled = false,
        hasError = false,
        isSubmitting = false,
        orderErrorRes = null,
        isOnline = true,
        onAddressChanged = {},
        onScanClick = {},
        onPasteClick = {},
        onBackClick = {},
        onContinueClick = {}
    )
}

@Preview(showBackground = true, widthDp = 393, heightDp = 760)
@Composable
private fun DEXRefundAddressScreenFilledPreview() {
    DEXRefundAddressScreenContent(
        address = "bc1qxhgnnp745zryn2ud8hm6k3mygkkpkm35020js0",
        currencyCode = "BTC",
        continueEnabled = true,
        hasError = false,
        isSubmitting = false,
        orderErrorRes = null,
        isOnline = true,
        onAddressChanged = {},
        onScanClick = {},
        onPasteClick = {},
        onBackClick = {},
        onContinueClick = {}
    )
}

@Preview(showBackground = true, widthDp = 393, heightDp = 760)
@Composable
private fun DEXRefundAddressScreenErrorPreview() {
    DEXRefundAddressScreenContent(
        address = "not-a-valid-address",
        currencyCode = "BTC",
        continueEnabled = true,
        hasError = true,
        isSubmitting = false,
        orderErrorRes = null,
        isOnline = true,
        onAddressChanged = {},
        onScanClick = {},
        onPasteClick = {},
        onBackClick = {},
        onContinueClick = {}
    )
}
