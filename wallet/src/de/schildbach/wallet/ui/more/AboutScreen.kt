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

package de.schildbach.wallet.ui.more

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.ListItem1
import org.dash.wallet.common.ui.components.ListItem10
import org.dash.wallet.common.ui.components.ListItem11
import org.dash.wallet.common.ui.components.LocalDashColors
import org.dash.wallet.common.ui.components.Menu
import org.dash.wallet.common.ui.components.MenuItem
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.NavBarBack
import org.dash.wallet.common.ui.components.Size
import org.dash.wallet.common.ui.components.Style

/**
 * Immutable view state for the About screen. The fragment builds this from the
 * existing ViewModel StateFlows (and pre-formats date / sync strings that need a
 * Context) so the composable stays pure and previewable.
 */
data class AboutUIState(
    val versionName: String = "",
    val dashjVersion: String = "",
    val platformVersion: String = "",
    val deviceSyncStatus: String = "",
    val serverUpdateStatus: String? = null,
    val firebaseInstallationId: String = "",
    val fcmToken: String = "",
    val showForceSyncButton: Boolean = false,
    val isMainNet: Boolean = true,
    val copyrightYear: Int = 0
)

/**
 * Middle-truncates a long opaque id (keeping [head] leading and [tail] trailing
 * characters) so both ends stay visible on one line, e.g. "cWbflOodTkmhtbsN…UF2PB".
 * Short strings are returned unchanged.
 */
private fun String.middleEllipsize(head: Int = 16, tail: Int = 16): String =
    if (length <= head + tail + 1) this else "${take(head)}…${takeLast(tail)}"

@Composable
fun AboutScreen(
    uiState: AboutUIState,
    onBackClick: () -> Unit = {},
    onForceSyncClick: () -> Unit = {},
    onFirebaseInstallationIdClick: () -> Unit = {},
    onFcmTokenClick: () -> Unit = {},
    onGithubLinkClick: () -> Unit = {},
    onReviewAndRateClick: () -> Unit = {},
    onContactSupportClick: () -> Unit = {}
) {
    val colors = LocalDashColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.backgroundPrimary)
    ) {
        // Fixed top navigation — back chevron only
        NavBarBack(onBackClick = onBackClick)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Centered Dash wordmark
            DashLogoHeader(isMainNet = uiState.isMainNet)

            // Info card: versions + Firebase ids + source link
            Menu {
                ListItem1(
                    label = stringResource(R.string.about_app_version_label),
                    value = uiState.versionName
                )
                ListItem1(
                    label = stringResource(R.string.about_dashj_label),
                    value = uiState.dashjVersion
                )
                ListItem1(
                    label = stringResource(R.string.about_platform_label),
                    value = uiState.platformVersion
                )
                ListItem11(
                    label = stringResource(R.string.about_firebase_installation_id),
                    primaryText = uiState.firebaseInstallationId,
                    onClick = onFirebaseInstallationIdClick
                )
                val fcmTokenText = uiState.fcmToken.ifBlank {
                    stringResource(R.string.about_value_not_available)
                }
                ListItem11(
                    // Show a middle-truncated token so both ends stay visible; the
                    // row's onClick still copies the full token from the ViewModel.
                    label = stringResource(R.string.about_fcm_token),
                    primaryText = fcmTokenText.middleEllipsize(),
                    onClick = onFcmTokenClick
                )
                // List10 source link — blue, clickable value (primaryColor).
                ListItem10(
                    secondaryText = stringResource(R.string.about_fork_disclaimer),
                    primaryText = stringResource(R.string.about_github_link),
                    primaryColor = colors.dashBlue,
                    onClick = onGithubLinkClick
                )
            }

            // Explore Dash card: sync diagnostics
            Menu {
                AboutSectionTitle(
                    text = stringResource(R.string.about_explore_section),
                    trailing = if (uiState.showForceSyncButton) {
                        { ForceSyncButton(onClick = onForceSyncClick) }
                    } else {
                        null
                    }
                )

                ListItem1(
                    label = stringResource(R.string.about_last_device_sync),
                    value = uiState.deviceSyncStatus
                )
                ListItem1(
                    label = stringResource(R.string.about_last_server_update),
                    value = uiState.serverUpdateStatus ?: ""
                )
            }

            // Feedback card
            Menu {
                MenuItem(
                    title = stringResource(R.string.about_review_and_rate),
                    icon = R.drawable.ic_rate_app_filled,
                    action = onReviewAndRateClick
                )
                MenuItem(
                    title = stringResource(R.string.about_contact_support),
                    icon = R.drawable.ic_contact_support_filled,
                    action = onContactSupportClick
                )
            }

            // Footer: copyright + license, centered
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.about_copyright, uiState.copyrightYear),
                    style = MyTheme.Body2Regular,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.about_license),
                    style = MyTheme.Body2Regular,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Centered Dash wordmark (tints the shared white logo). Blue on production
 * (mainnet) builds, orange on non-production builds so they are visually distinct.
 */
@Composable
private fun DashLogoHeader(isMainNet: Boolean) {
    val colors = LocalDashColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_dash_logo_white),
            contentDescription = null,
            colorFilter = ColorFilter.tint(
                if (isMainNet) colors.dashBlue else colors.orange
            ),
            modifier = Modifier
                .height(28.dp)
                .aspectRatio(128f / 36f)
        )
    }
}

/**
 * Card section heading (Body M Medium, tertiary), e.g. "Explore Dash", with an
 * optional [trailing] slot rendered at the end of the row (e.g. the Update button).
 */
@Composable
private fun AboutSectionTitle(
    text: String,
    trailing: (@Composable () -> Unit)? = null
) {
    val colors = LocalDashColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // heightIn (not vertical padding) governs the height so the 36dp Update
            // button fits inside the 46dp header instead of stacking onto the padding.
            .heightIn(min = 46.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MyTheme.Body2Medium,
            color = colors.textTertiary
        )
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}

/**
 * Testnet-only force-sync affordance. Shown as a blue "Update" button in the
 * "Explore Dash" section header. Not part of the production Figma design — it is a
 * debug tool retained from the original screen and gated to non-mainnet builds.
 */
@Composable
private fun ForceSyncButton(onClick: () -> Unit) {
    DashButton(
        // DashButton (Small) has 12dp internal horizontal padding; offset it so the
        // button's trailing edge lines up with the value column in the rows below.
        modifier = Modifier.offset(x = Size.Small.paddingHorizontal),
        text = stringResource(R.string.about_explore_update),
        leadingIcon = ImageVector.vectorResource(R.drawable.ic_refresh_blue),
        style = Style.PlainBlue,
        size = Size.Small,
        stretch = false,
        onClick = onClick
    )
}

@Composable
@Preview(showBackground = true, widthDp = 393)
private fun AboutScreenPreview() {
    AboutScreen(
        uiState = AboutUIState(
            versionName = "11.8.2 (10)",
            dashjVersion = "22.0.3",
            platformVersion = "4.0.0",
            deviceSyncStatus = "01 Jan 2026 at 20:00",
            serverUpdateStatus = "01 Jan 2026",
            firebaseInstallationId = "fxUBdkvxQhO-ICxXXXN5mAI",
            fcmToken = "fxUBdkvxQhO-ICxXXXN5mAI:APA91bFxb4Zn9gbNfaN-rJDGQRFKX3yuQUF2PB",
            showForceSyncButton = true,
            isMainNet = true,
            copyrightYear = 2026
        )
    )
}

@Composable
@Preview(showBackground = true, widthDp = 393)
private fun AboutScreenTestNetPreview() {
    AboutScreen(
        uiState = AboutUIState(
            versionName = "11.8.2 (10)",
            dashjVersion = "22.0.3",
            platformVersion = "4.0.0",
            deviceSyncStatus = "01 Jan 2026 at 20:00",
            serverUpdateStatus = "01 Jan 2026",
            firebaseInstallationId = "fxUBdkvxQhO-ICxXXXN5mAI",
            fcmToken = "fxUBdkvxQhO-ICxXXXN5mAI:APA91bFxb4Zn9gbNfaN-rJDGQRFKX3yuQUF2PB",
            showForceSyncButton = true,
            isMainNet = false,
            copyrightYear = 2026
        )
    )
}

@Composable
@Preview(showBackground = true, widthDp = 393)
private fun AboutScreenLoadingPreview() {
    AboutScreen(
        uiState = AboutUIState(
            versionName = "7.4.7 (10)",
            dashjVersion = "22.0.3",
            platformVersion = "4.0.0",
            deviceSyncStatus = "Syncing…",
            serverUpdateStatus = null,
            firebaseInstallationId = "fxUBdkvxQhO-ICxXXXN5mAI",
            fcmToken = "fxUBdkvxQhO-ICxXXXN5mAI:A...N-rJDGQRFKX3yuQUF2PB",
            showForceSyncButton = false,
            copyrightYear = 2026
        )
    )
}