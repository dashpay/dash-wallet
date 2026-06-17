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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.components.Menu
import org.dash.wallet.common.ui.components.MenuItem
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.NavBarBack

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
    val copyrightYear: Int = 0
)

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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MyTheme.Colors.backgroundPrimary)
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
            DashLogoHeader()

            // Info card: versions + Firebase ids + source link
            Menu {
                AboutInfoRow(
                    label = stringResource(R.string.about_app_version_label),
                    value = uiState.versionName
                )
                AboutInfoRow(
                    label = stringResource(R.string.about_dashj_label),
                    value = uiState.dashjVersion
                )
                AboutInfoRow(
                    label = stringResource(R.string.about_platform_label),
                    value = uiState.platformVersion
                )
                AboutStackedRow(
                    topText = stringResource(R.string.about_firebase_installation_id),
                    bottomText = uiState.firebaseInstallationId,
                    onClick = onFirebaseInstallationIdClick
                )
                val fcmTokenText = uiState.fcmToken.ifBlank {
                    stringResource(R.string.about_value_not_available)
                }
                AboutStackedRow(
                    topText = stringResource(R.string.about_fcm_token),
                    bottomText = fcmTokenText,
                    bottomMaxLines = 2,
                    onClick = onFcmTokenClick
                )
                AboutStackedRow(
                    topText = stringResource(R.string.about_fork_disclaimer),
                    bottomText = stringResource(R.string.about_github_link),
                    topStyle = MyTheme.Body2Regular,
                    topColor = MyTheme.Colors.textPrimary,
                    bottomColor = MyTheme.Colors.dashBlue,
                    onClick = onGithubLinkClick
                )
            }

            // Explore Dash card: sync diagnostics
            Menu {
                AboutSectionTitle(stringResource(R.string.about_explore_section))

                val forceSyncTrailing: (@Composable () -> Unit)? =
                    if (uiState.showForceSyncButton) {
                        { ForceSyncButton(onClick = onForceSyncClick) }
                    } else {
                        null
                    }
                AboutInfoRow(
                    label = stringResource(R.string.about_last_device_sync),
                    value = uiState.deviceSyncStatus,
                    trailing = forceSyncTrailing
                )
                AboutInfoRow(
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
                    color = MyTheme.Colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.about_license),
                    style = MyTheme.Body2Regular,
                    color = MyTheme.Colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** Centered blue Dash wordmark (tints the shared white logo). */
@Composable
private fun DashLogoHeader() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_dash_logo_white),
            contentDescription = null,
            colorFilter = ColorFilter.tint(MyTheme.Colors.dashBlue),
            modifier = Modifier
                .height(28.dp)
                .aspectRatio(128f / 36f)
        )
    }
}

/**
 * Figma "List1" — horizontal key/value row: label (Body M Medium, tertiary,
 * single line) on the left and value (Body M Regular, primary) right-aligned.
 * Optional [trailing] content (e.g. the testnet force-sync button).
 */
@Composable
private fun AboutInfoRow(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .heightIn(min = 46.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MyTheme.Body2Medium,
            color = MyTheme.Colors.textTertiary,
            maxLines = 1
        )
        Text(
            text = value,
            style = MyTheme.Body2Regular,
            color = MyTheme.Colors.textPrimary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

/**
 * Figma "List11" / "List10" — stacked row: a top line over a bottom value line
 * (gap 2dp). Defaults render the List11 variant (Body M Medium tertiary label
 * over Body M Regular primary value); override [topStyle]/[topColor]/[bottomColor]
 * for the List10 source-link variant (regular primary text over a blue link).
 */
@Composable
private fun AboutStackedRow(
    topText: String,
    bottomText: String,
    topStyle: TextStyle = MyTheme.Body2Medium,
    topColor: Color = MyTheme.Colors.textTertiary,
    bottomColor: Color = MyTheme.Colors.textPrimary,
    bottomMaxLines: Int = Int.MAX_VALUE,
    onClick: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = topText,
            style = topStyle,
            color = topColor,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = bottomText,
            style = MyTheme.Body2Regular,
            color = bottomColor,
            maxLines = bottomMaxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** Card section heading (Body M Medium, tertiary), e.g. "Explore Dash". */
@Composable
private fun AboutSectionTitle(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = text,
            style = MyTheme.Body2Medium,
            color = MyTheme.Colors.textTertiary
        )
    }
}

/**
 * Testnet-only force-sync affordance shown as the trailing content of the
 * "Last device sync" row. Not part of the Figma design — it is a debug tool
 * retained from the original screen and gated to non-mainnet builds.
 */
@Composable
private fun ForceSyncButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clickable { onClick() }
            .background(MyTheme.Colors.primary5, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_refresh_white_24dp),
            contentDescription = null,
            tint = MyTheme.Colors.textTertiary,
            modifier = Modifier.size(20.dp)
        )
    }
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
            fcmToken = "fxUBdkvxQhO-ICxXXXN5mAI:A...N-rJDGQRFKX3yuQUF2PB",
            showForceSyncButton = true,
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