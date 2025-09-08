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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import de.schildbach.wallet_test.BuildConfig
import kotlinx.coroutines.flow.StateFlow
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.components.Menu
import org.dash.wallet.common.ui.components.MenuItem
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.TopIntro
import org.dash.wallet.common.ui.components.TopNavBase

@Composable
fun SecurityScreen(
    onBackClick: () -> Unit = {},
    onBackupWalletClick: () -> Unit = {},
    onViewRecoveryPhraseClick: () -> Unit = {},
    onChangePinClick: () -> Unit = {},
    onFingerprintAuthClick: (Boolean) -> Unit = {},
    onFaceIdClick: () -> Unit = {},
    onAutohideBalanceClick: (Boolean) -> Unit = {},
    onAdvancedSecurityClick: () -> Unit = {},
    onResetWalletClick: () -> Unit = {},
    onBackupWalletToFileClick: () -> Unit = {}
) {
    val viewModel: SecurityViewModel = hiltViewModel()
    
    SecurityScreen(
        uiStateFlow = viewModel.uiState,
        onBackClick = onBackClick,
        onBackupWalletClick = onBackupWalletClick,
        onViewRecoveryPhraseClick = onViewRecoveryPhraseClick,
        onChangePinClick = onChangePinClick,
        onFingerprintAuthClick = onFingerprintAuthClick,
        onFaceIdClick = onFaceIdClick,
        onAutohideBalanceClick = onAutohideBalanceClick,
        onAdvancedSecurityClick = onAdvancedSecurityClick,
        onResetWalletClick = onResetWalletClick,
        onBackupWalletToFileClick = onBackupWalletToFileClick
    )
}

@Composable
fun SecurityScreen(
    uiStateFlow: StateFlow<SecurityUIState>,
    onBackClick: () -> Unit = {},
    onBackupWalletClick: () -> Unit = {},
    onViewRecoveryPhraseClick: () -> Unit = {},
    onChangePinClick: () -> Unit = {},
    onFingerprintAuthClick: (Boolean) -> Unit = {},
    onFaceIdClick: () -> Unit = {},
    onAutohideBalanceClick: (Boolean) -> Unit = {},
    onAdvancedSecurityClick: () -> Unit = {},
    onResetWalletClick: () -> Unit = {},
    onBackupWalletToFileClick: () -> Unit = {}
) {
    val uiState by uiStateFlow.collectAsState()
    
    SecurityScreenContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onBackupWalletClick = onBackupWalletClick,
        onViewRecoveryPhraseClick = onViewRecoveryPhraseClick,
        onChangePinClick = onChangePinClick,
        onFingerprintAuthClick = onFingerprintAuthClick,
        onFaceIdClick = onFaceIdClick,
        onAutohideBalanceClick = onAutohideBalanceClick,
        onAdvancedSecurityClick = onAdvancedSecurityClick,
        onResetWalletClick = onResetWalletClick,
        onBackupWalletToFileClick = onBackupWalletToFileClick
    )
}

@Composable
private fun SecurityScreenContent(
    uiState: SecurityUIState,
    onBackClick: () -> Unit = {},
    onBackupWalletClick: () -> Unit = {},
    onViewRecoveryPhraseClick: () -> Unit = {},
    onChangePinClick: () -> Unit = {},
    onFingerprintAuthClick: (Boolean) -> Unit = {},
    onFaceIdClick: () -> Unit = {},
    onAutohideBalanceClick: (Boolean) -> Unit = {},
    onAdvancedSecurityClick: () -> Unit = {},
    onResetWalletClick: () -> Unit = {},
    onBackupWalletToFileClick: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MyTheme.Colors.backgroundPrimary)
    ) {
        // Top Navigation
        TopNavBase(
            leadingIcon = ImageVector.vectorResource(R.drawable.ic_menu_chevron),
            onLeadingClick = onBackClick,
            centralPart = false,
            trailingPart = false
        )

        // Security Header
        TopIntro(
            heading = stringResource(R.string.security_title),
            modifier = Modifier.padding(top = 10.dp, start = 20.dp, end = 20.dp, bottom = 20.dp)
        )

        // Scrollable Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Menu {
                // Backup Wallet
                if (uiState.needPassphraseBackup) {
                    MenuItem(
                        title = stringResource(R.string.activity_security_backup_wallet),
                        icon = R.drawable.ic_backup_wallet,
                        action = onBackupWalletClick
                    )
                }

                // View Recovery Phrase
                MenuItem(
                    title = stringResource(R.string.activity_security_view_recovery_phrase),
                    icon = R.drawable.ic_view_rec_phrase,
                    action = onViewRecoveryPhraseClick
                )

                // Change PIN
                MenuItem(
                    title = stringResource(R.string.wallet_options_encrypt_keys_change),
                    icon = R.drawable.ic_change_pin,
                    action = onChangePinClick
                )

                // Fingerprint Authentication
                if (uiState.fingerprintIsAvailable) {
                    MenuItem(
                        title = stringResource(R.string.activity_security_biometric_auth),
                        icon = R.drawable.ic_fingerprint_blue,
                        onToggleChanged = onFingerprintAuthClick,
                        isToggled = { uiState.fingerprintIsEnabled }
                    )
                }

                // Autohide Balance
                MenuItem(
                    title = stringResource(R.string.activity_security_hide_balance),
                    icon = R.drawable.ic_autohide_balance,
                    onToggleChanged = onAutohideBalanceClick,
                    isToggled = { uiState.hideBalance }
                )

                // Advanced Security
                MenuItem(
                    title = stringResource(R.string.activity_security_advanced),
                    icon = R.drawable.ic_advanced_security,
                    action = onAdvancedSecurityClick
                )

                // Reset Wallet (using generic rescan text for now)
                MenuItem(
                    title = stringResource(R.string.reset_wallet_text),
                    icon = R.drawable.ic_reset_wallet,
                    action = onResetWalletClick
                )
                if (BuildConfig.DEBUG) {
                    // Backup Wallet
                    MenuItem(
                        title = stringResource(R.string.activity_security_backup_wallet_to_file),
                        icon = R.drawable.ic_backup_wallet,
                        action = onBackupWalletToFileClick
                    )
                }
            }
        }
    }
}

@Composable
@Preview
fun SecurityScreenPreview() {
    SecurityScreenContent(uiState = SecurityUIState(needPassphraseBackup = true))
}

@Composable
@Preview(name = "Security with fingerprint enabled")
fun SecurityScreenPreviewWithFingerprint() {
    val customState = SecurityUIState(
        fingerprintIsEnabled = true,
        fingerprintIsAvailable = true
    )
    SecurityScreenContent(uiState = customState)
}