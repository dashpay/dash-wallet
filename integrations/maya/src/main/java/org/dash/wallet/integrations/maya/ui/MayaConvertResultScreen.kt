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

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.Size
import org.dash.wallet.common.ui.components.Style
import org.dash.wallet.integrations.maya.R
import org.dash.wallet.common.R as CommonR

/**
 * UI state for the Maya transaction-result screen. No Figma design exists for this screen;
 * it mirrors the old view-based maya_convert_result_fragment.xml using design-system components.
 *
 * The host fragment owns the state: it translates the transaction type + outcome into the
 * display fields (title, message, support/explorer visibility, button label) exactly like the
 * old view-based fragment did.
 */
data class MayaConvertResultUIState(
    /** True while the result is being determined; shows a centered spinner. */
    val isLoading: Boolean = true,
    /** Drives the icon and the title color (green for success, red for failure). */
    val isSuccess: Boolean = false,
    val title: String = "",
    val message: String = "",
    /** Shows the "Contact Maya support" button (error states). */
    val showContactSupport: Boolean = false,
    /** Explorer link under the result (successful swaps, and errors where a transaction
     *  was generated); null hides it. */
    val explorerLinkText: String? = null,
    /** Bottom button label: Close (success) or Retry (failure). */
    val buttonText: String = ""
)

@Composable
fun MayaConvertResultScreen(
    state: MayaConvertResultUIState,
    onButtonClick: () -> Unit,
    onContactSupportClick: () -> Unit,
    onExplorerLinkClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MyTheme.Colors.backgroundSecondary)
    ) {
        if (state.isLoading) {
            CircularProgressIndicator(
                color = MyTheme.Colors.dashBlue,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            // Result content, centered in the space above the bottom button (Figma 34195:9065).
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .padding(bottom = 66.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(
                        if (state.isSuccess) {
                            CommonR.drawable.ic_success_green
                        } else {
                            CommonR.drawable.ic_error
                        }
                    ),
                    contentDescription = null,
                    modifier = Modifier
                        .padding(top = 20.dp, bottom = 10.dp)
                        .size(90.dp)
                )

                Text(
                    text = state.title,
                    style = MyTheme.Typography.HeadlineMediumBold,
                    color = MyTheme.Colors.textPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp)
                        .padding(horizontal = 60.dp)
                )

                Text(
                    text = state.message,
                    style = MyTheme.Typography.TitleMedium,
                    color = MyTheme.Colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .padding(horizontal = 60.dp)
                )

                if (state.showContactSupport) {
                    DashButton(
                        text = stringResource(R.string.contact_maya_support),
                        leadingIcon = ImageVector.vectorResource(CommonR.drawable.ic_blue_support),
                        style = Style.TintedBlue,
                        size = Size.Small,
                        stretch = false,
                        onClick = onContactSupportClick,
                        modifier = Modifier.padding(top = 20.dp)
                    )
                }

                if (state.explorerLinkText != null) {
                    Text(
                        text = state.explorerLinkText,
                        style = MyTheme.CaptionMedium,
                        color = MyTheme.Colors.dashBlue,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(top = 5.dp)
                            .clickable(onClick = onExplorerLinkClick)
                            .padding(5.dp)
                    )
                }
            }

            DashButton(
                text = state.buttonText,
                style = Style.FilledBlue,
                size = Size.Large,
                onClick = onButtonClick,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 60.dp)
                    .padding(bottom = 20.dp)
            )
        }
    }
}

// ── Previews ────────────────────────────────────────────────────────────────────

@Preview(showBackground = true, widthDp = 393, heightDp = 760)
@Composable
private fun MayaConvertResultSuccessPreview() {
    MayaConvertResultScreen(
        state = MayaConvertResultUIState(
            isLoading = false,
            isSuccess = true,
            title = "You successfully converted DASH to BTC",
            explorerLinkText = "View on mayascan.org",
            buttonText = "Done"
        ),
        onButtonClick = {},
        onContactSupportClick = {},
        onExplorerLinkClick = {}
    )
}

@Preview(showBackground = true, widthDp = 393, heightDp = 760)
@Composable
private fun MayaConvertResultErrorPreview() {
    MayaConvertResultScreen(
        state = MayaConvertResultUIState(
            isLoading = false,
            isSuccess = false,
            title = "Transfer failed",
            message = "The transaction could not be completed. Please try again.",
            showContactSupport = true,
            buttonText = "Retry"
        ),
        onButtonClick = {},
        onContactSupportClick = {},
        onExplorerLinkClick = {}
    )
}

@Preview(showBackground = true, widthDp = 393, heightDp = 760)
@Composable
private fun MayaConvertResultLoadingPreview() {
    MayaConvertResultScreen(
        state = MayaConvertResultUIState(isLoading = true),
        onButtonClick = {},
        onContactSupportClick = {},
        onExplorerLinkClick = {}
    )
}
