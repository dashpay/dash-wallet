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

package de.schildbach.wallet.ui.more.masternode_keys

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.schildbach.wallet_test.R
import kotlinx.coroutines.flow.StateFlow
import org.bitcoinj.wallet.authentication.AuthenticationKeyStatus
import org.dash.wallet.common.ui.components.ListItem
import org.dash.wallet.common.ui.components.Menu
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.NavBarBackPlus
import org.dash.wallet.common.ui.components.TopIntro

@Composable
fun MasternodeKeyChainScreen(
    uiStateFlow: StateFlow<MasternodeKeyChainUIState>,
    onBackClick: () -> Unit,
    onAddKeyClick: () -> Unit,
    onCopy: (String) -> Unit
) {
    val uiState by uiStateFlow.collectAsState()
    MasternodeKeyChainScreenContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onAddKeyClick = onAddKeyClick,
        onCopy = onCopy
    )
}

@Composable
private fun MasternodeKeyChainScreenContent(
    uiState: MasternodeKeyChainUIState,
    onBackClick: () -> Unit = {},
    onAddKeyClick: () -> Unit = {},
    onCopy: (String) -> Unit = {}
) {
    val keyTypeName = uiState.keyType?.let {
        stringResource(
            when (it) {
                MasternodeKeyType.OWNER -> R.string.masternode_key_type_owner
                MasternodeKeyType.VOTING -> R.string.masternode_key_type_voting
                MasternodeKeyType.OPERATOR -> R.string.masternode_key_type_operator
                MasternodeKeyType.PLATFORM -> R.string.masternode_key_type_platform
            }
        )
    } ?: ""

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MyTheme.Colors.backgroundPrimary)
    ) {
        NavBarBackPlus(
            onBackClick = onBackClick,
            onPlusClick = onAddKeyClick
        )

        TopIntro(
            heading = keyTypeName,
            modifier = Modifier.padding(top = 10.dp, start = 20.dp, end = 20.dp, bottom = 20.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            uiState.keypairs.forEach { keypair ->
                KeypairSection(keypair = keypair, onCopy = onCopy)
            }
        }
    }
}

@Composable
private fun KeypairSection(
    keypair: KeypairEntry,
    onCopy: (String) -> Unit
) {
    val usageText = when (keypair.usageStatus) {
        AuthenticationKeyStatus.CURRENT -> stringResource(
            R.string.masternode_key_used,
            keypair.usageIpAddress ?: stringResource(R.string.masternode_key_ip_address_unknown)
        )
        AuthenticationKeyStatus.REVOKED -> stringResource(R.string.masternode_key_revoked)
        else -> stringResource(R.string.masternode_key_not_used)
    }

    Column(
        modifier = Modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = stringResource(R.string.masternode_key_pair_index, keypair.index),
                style = MyTheme.SubtitleSemibold,
                color = MyTheme.Colors.textPrimary
            )
            Text(
                text = usageText,
                style = MyTheme.Caption,
                color = MyTheme.Colors.textTertiary
            )
        }

        Menu {
            keypair.fields.forEach { field ->
                KeyFieldRow(field = field, onCopy = onCopy)
            }
        }
    }
}

@Composable
fun TableListExtendedPublicKeyItem(label: String, value: String, onCopy: (String) -> Unit) {
    ListItem(
        helpTextAbove = label,
        title = value,
        trailingContent = {
            Icon(
                painter = painterResource(id = R.drawable.ic_copy),
                contentDescription = null,
                tint = MyTheme.Colors.textTertiary,
                modifier = Modifier.size(16.dp)
            )
        },
        onClick = { onCopy(value) }
    )
}

@Composable
private fun KeyFieldRow(
    field: KeyFieldEntry,
    onCopy: (String) -> Unit
) {
    val label = stringResource(
        when (field.type) {
            KeyFieldType.ADDRESS -> R.string.masternode_key_address
            KeyFieldType.KEY_ID -> R.string.masternode_key_id
            KeyFieldType.PUBLIC_KEY -> R.string.masternode_key_public
            KeyFieldType.PUBLIC_KEY_LEGACY -> R.string.masternode_key_public_legacy
            KeyFieldType.PRIVATE_KEY_HEX -> R.string.masternode_key_private_hex
            KeyFieldType.PRIVATE_KEY_WIF -> R.string.masternode_key_private_wif
            KeyFieldType.PRIVATE_PUBLIC_BASE64 -> R.string.masternode_key_private_public_base64
        }
    )

    if (field.value != null) {
        TableListExtendedPublicKeyItem(
            label = label,
            value = field.value,
            onCopy = { onCopy(field.value) }
        )
    } else {
        ListItem(
            helpTextAbove = label,
            title = "",
            trailingContent = {
                LinearProgressIndicator(
                    modifier = Modifier.width(60.dp),
                    color = MyTheme.Colors.dashBlue,
                    trackColor = MyTheme.Colors.dashBlue.copy(alpha = 0.2f)
                )
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MasternodeKeyChainScreenPreview() {
    MasternodeKeyChainScreenContent(
        uiState = MasternodeKeyChainUIState(
            keyType = MasternodeKeyType.OWNER,
            keypairs = listOf(
                KeypairEntry(
                    index = 0,
                    usageStatus = null,
                    usageIpAddress = null,
                    fields = listOf(
                        KeyFieldEntry(KeyFieldType.ADDRESS, "XuuRQMVEK9fQMsoAegE32Bdc1XvHhAiWa9"),
                        KeyFieldEntry(KeyFieldType.PUBLIC_KEY, "03eeda68f0eb482935c7ecbebf7b6497756e471b7a0fad5014bbe6ab593cb6127"),
                        KeyFieldEntry(KeyFieldType.PRIVATE_KEY_HEX, null),
                        KeyFieldEntry(KeyFieldType.PRIVATE_KEY_WIF, null)
                    )
                )
            )
        )
    )
}