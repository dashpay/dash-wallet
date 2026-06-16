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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.components.ListItem
import org.dash.wallet.common.ui.components.Menu
import org.dash.wallet.common.ui.components.MenuItem
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.NavBarBack
import org.dash.wallet.common.ui.components.TopIntro

/**
 * Immutable view state for the About screen. The fragment builds this from the
 * existing ViewModel LiveData (and pre-formats date / sync strings that need a
 * Context) so the composable stays pure and previewable.
 */
data class AboutUIState(
    val title: String = "",
    val versionName: String = "",
    val buildNumber: String = "",
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
        // Top navigation — back chevron only
        NavBarBack(onBackClick = onBackClick)

        // Heading + version block (TopIntro pattern, multi-line)
        AboutHeader(uiState = uiState)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {


            // Diagnostics card — Figma List10 stacked rows (secondary label / primary value)
            Menu {
                val forceSyncTrailing: (@Composable () -> Unit)? =
                    if (uiState.showForceSyncButton) {
                        { ForceSyncButton(onClick = onForceSyncClick) }
                    } else {
                        null
                    }
                ListItem(
                    helpTextAbove = stringResource(R.string.about_last_explore_device_sync),
                    title = uiState.deviceSyncStatus,
                    trailingContent = forceSyncTrailing
                )

                ListItem(
                    helpTextAbove = stringResource(R.string.about_last_explore_server_update),
                    title = uiState.serverUpdateStatus ?: ""
                )

                ListItem(
                    helpTextAbove = stringResource(R.string.about_firebase_installation_id),
                    title = uiState.firebaseInstallationId,
                    onClick = onFirebaseInstallationIdClick
                )

                ListItem(
                    helpTextAbove = stringResource(R.string.about_fcm_token),
                    title = uiState.fcmToken,
                    onClick = onFcmTokenClick
                )

                ListItem(
                    helpTextAbove = stringResource(R.string.about_fork_disclaimer),
                    title = stringResource(R.string.about_github_link),
                    titleColor = MyTheme.Colors.dashBlue,
                    onClick = onGithubLinkClick
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

/**
 * Heading + version description block matching the Figma TopIntro variant
 * (heading, main description in primary text, secondary description in
 * secondary text). The shared [org.dash.wallet.common.ui.components.TopIntro]
 * only supports a single description line, so this block is built inline.
 */
@Composable
private fun AboutHeader(uiState: AboutUIState) {
    // Main description: app version + build number
    val mainDescription = if (uiState.buildNumber.isNotEmpty()) {
        "${uiState.versionName} ${uiState.buildNumber}"
    } else {
        uiState.versionName
    }
    TopIntro(
        heading = uiState.title,
        text = mainDescription,
        textTwo = uiState.dashjVersion,
        textThree = uiState.platformVersion
    )
//    Column(
//        modifier = Modifier
//            .fillMaxWidth()
//            .padding(top = 10.dp, bottom = 10.dp)
//            .padding(horizontal = 20.dp),
//        verticalArrangement = Arrangement.spacedBy(4.dp),
//        horizontalAlignment = Alignment.Start
//    ) {
//        // Secondary description: dashj version
//        Text(
//            text = uiState.dashjVersion,
//            style = MyTheme.Body2Regular,
//            color = MyTheme.Colors.textSecondary,
//            modifier = Modifier.fillMaxWidth()
//        )
//
//        // Secondary description: platform version
//        if (uiState.platformVersion.isNotEmpty()) {
//            Text(
//                text = uiState.platformVersion,
//                style = MyTheme.Body2Regular,
//                color = MyTheme.Colors.textSecondary,
//                modifier = Modifier.fillMaxWidth()
//            )
//        }
//    }
}

/**
 * Testnet-only force-sync affordance shown as the trailing content of the
 * "Last Explore device sync" row. Not part of the Figma design — it is a debug
 * tool retained from the original screen and gated to non-mainnet builds.
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
            title = "About Dash",
            versionName = "Dash Wallet 7.4.7",
            buildNumber = "(10)",
            dashjVersion = "dashj 23.11, a Dash protocol implementation",
            platformVersion = "Platform 4.0.0",
            deviceSyncStatus = "Apr 24, 4:59 PM",
            serverUpdateStatus = "Apr 10, 6:30 PM",
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
            title = "About Dash",
            versionName = "Dash Wallet 7.4.7",
            buildNumber = "(10)",
            dashjVersion = "dashj 0.17.11-SNAPSHOT, a Dash protocol implementation",
            platformVersion = "Platform 1.5.1",
            deviceSyncStatus = "Syncing…",
            serverUpdateStatus = null,
            firebaseInstallationId = "fxUBdkvxQhO-ICxXXXN5mAI",
            fcmToken = "fxUBdkvxQhO-ICxXXXN5mAI:A...N-rJDGQRFKX3yuQUF2PB",
            showForceSyncButton = false,
            copyrightYear = 2026
        )
    )
}