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

package de.schildbach.wallet.install

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.DashWalletTheme
import org.dash.wallet.common.ui.components.FeatureTopText
import org.dash.wallet.common.ui.components.LocalDashColors

/**
 * The only screen of an install that lacks its native libraries (see
 * [WalletAppComponentFactory]): it stands in for every activity and sends the
 * user to Google Play. It is not declared in the manifest; the factory
 * instantiates it in place of whichever activity the system asked for, so it runs
 * under that activity's manifest entry.
 */
class IncompleteInstallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // The declared activity's theme may be translucent or a splash; use the app theme.
        setTheme(R.style.My_Theme)
        super.onCreate(savedInstanceState)
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
        setContent {
            DashWalletTheme {
                IncompleteInstallScreen(onOpenPlay = ::openGooglePlay)
            }
        }
    }

    private fun openGooglePlay() {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
        try {
            startActivity(market)
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
                    )
                )
            } catch (e: ActivityNotFoundException) {
                // No store and no browser: the text on screen still says what to do.
            }
        }
    }
}

@Composable
private fun IncompleteInstallScreen(onOpenPlay: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalDashColors.current.backgroundPrimary)
            .systemBarsPadding()
            .padding(horizontal = 20.dp, vertical = 20.dp)
    ) {
        // The text takes the space above the button and scrolls inside it, so with
        // large accessibility text the button (the screen's only action) and the
        // whole uninstall warning both stay reachable.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                FeatureTopText(
                    heading = stringResource(R.string.incomplete_install_title),
                    text = stringResource(R.string.incomplete_install_message)
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        DashButton(
            modifier = Modifier.fillMaxWidth(),
            text = stringResource(R.string.incomplete_install_open_play),
            onClick = onOpenPlay
        )
    }
}

@Preview
@Composable
private fun IncompleteInstallScreenPreview() {
    DashWalletTheme {
        IncompleteInstallScreen(onOpenPlay = {})
    }
}
