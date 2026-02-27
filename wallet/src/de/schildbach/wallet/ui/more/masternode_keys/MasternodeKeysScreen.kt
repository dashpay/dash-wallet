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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.schildbach.wallet_test.R
import kotlinx.coroutines.flow.StateFlow
import org.dash.wallet.common.ui.components.Menu
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.TopIntro
import org.dash.wallet.common.ui.components.TopNavBase

@Composable
fun MasternodeKeysScreen(
    uiStateFlow: StateFlow<MasternodeKeysUIState>,
    onBackClick: () -> Unit,
    onKeyTypeClick: (MasternodeKeyType) -> Unit
) {
    val uiState by uiStateFlow.collectAsState()
    MasternodeKeysScreenContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onKeyTypeClick = onKeyTypeClick
    )
}

@Composable
private fun MasternodeKeysScreenContent(
    uiState: MasternodeKeysUIState,
    onBackClick: () -> Unit = {},
    onKeyTypeClick: (MasternodeKeyType) -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MyTheme.Colors.backgroundPrimary)
    ) {
        TopNavBase(
            leadingIcon = ImageVector.vectorResource(R.drawable.ic_menu_chevron),
            onLeadingClick = onBackClick,
            centralPart = false,
            trailingPart = false
        )

        TopIntro(
            heading = stringResource(R.string.masternode_keys_title),
            modifier = Modifier.padding(top = 10.dp, start = 20.dp, end = 20.dp, bottom = 20.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Menu {
                uiState.keyTypes.forEach { info ->
                    MasternodeKeyTypeRow(
                        info = info,
                        onClick = { onKeyTypeClick(info.type) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MasternodeKeyTypeRow(
    info: MasternodeKeyTypeInfo,
    onClick: () -> Unit
) {
    val typeName = stringResource(
        when (info.type) {
            MasternodeKeyType.OWNER -> R.string.masternode_key_type_owner
            MasternodeKeyType.VOTING -> R.string.masternode_key_type_voting
            MasternodeKeyType.OPERATOR -> R.string.masternode_key_type_operator
            MasternodeKeyType.PLATFORM -> R.string.masternode_key_type_platform
        }
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clickable { onClick() }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = typeName,
                style = MyTheme.Body2Medium,
                color = MyTheme.Colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.masternode_key_type_total, info.totalKeys),
                style = MyTheme.OverlineMedium,
                color = MyTheme.Colors.textSecondary,
                maxLines = 1
            )
        }

        Text(
            text = stringResource(R.string.masternode_key_type_used, info.usedKeys),
            style = MyTheme.Caption,
            color = MyTheme.Colors.textSecondary,
            modifier = Modifier.padding(end = 10.dp)
        )

        Icon(
            painter = painterResource(id = R.drawable.ic_menu_row_arrow),
            contentDescription = null,
            tint = MyTheme.Colors.textTertiary,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MasternodeKeysScreenPreview() {
    MasternodeKeysScreenContent(
        uiState = MasternodeKeysUIState(
            keyTypes = listOf(
                MasternodeKeyTypeInfo(MasternodeKeyType.OWNER, 5, 3),
                MasternodeKeyTypeInfo(MasternodeKeyType.VOTING, 3, 1),
                MasternodeKeyTypeInfo(MasternodeKeyType.OPERATOR, 2, 0),
                MasternodeKeyTypeInfo(MasternodeKeyType.PLATFORM, 1, 0)
            )
        )
    )
}