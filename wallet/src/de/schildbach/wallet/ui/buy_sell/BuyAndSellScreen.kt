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

package de.schildbach.wallet.ui.buy_sell

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import de.schildbach.wallet.data.BuyAndSellDashServicesModel
import de.schildbach.wallet.data.ServiceStatus
import de.schildbach.wallet.data.ServiceType
import de.schildbach.wallet_test.R
import kotlinx.coroutines.flow.StateFlow
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.ui.components.Menu
import org.dash.wallet.common.ui.components.MenuItem
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.ToastImageResource
import org.dash.wallet.common.ui.components.TopIntro
import org.dash.wallet.common.ui.components.TopNavBase
import org.dash.wallet.common.util.toFormattedString

@Composable
fun BuyAndSellScreen(
    onBackClick: () -> Unit = {},
    onTopperClick: () -> Unit = {},
    onUpholdClick: () -> Unit = {},
    onCoinbaseClick: () -> Unit = {},
    onMayaClick: () -> Unit = {}
) {
    val viewModel: BuyAndSellViewModel = hiltViewModel()

    BuyAndSellScreen(
        uiStateFlow = viewModel.uiState,
        onBackClick = onBackClick,
        onTopperClick = onTopperClick,
        onUpholdClick = onUpholdClick,
        onCoinbaseClick = onCoinbaseClick,
        onMayaClick = onMayaClick
    )
}

@Composable
fun BuyAndSellScreen(
    uiStateFlow: StateFlow<BuyAndSellUIState>,
    onBackClick: () -> Unit = {},
    onTopperClick: () -> Unit = {},
    onUpholdClick: () -> Unit = {},
    onCoinbaseClick: () -> Unit = {},
    onMayaClick: () -> Unit = {}
) {
    val uiState by uiStateFlow.collectAsState()

    BuyAndSellScreenContent(
        services = uiState.servicesList,
        isConnected = uiState.isConnected,
        balanceFormat = uiState.balanceFormat,
        hasValidCredentials = uiState.hasValidCredentials,
        onBackClick = onBackClick,
        onTopperClick = onTopperClick,
        onUpholdClick = onUpholdClick,
        onCoinbaseClick = onCoinbaseClick,
        onMayaClick = onMayaClick
    )
}

@Composable
private fun BuyAndSellScreenContent(
    services: List<BuyAndSellDashServicesModel>,
    isConnected: Boolean,
    balanceFormat: MonetaryFormat,
    hasValidCredentials: Boolean = true,
    onBackClick: () -> Unit = {},
    onTopperClick: () -> Unit = {},
    onUpholdClick: () -> Unit = {},
    onCoinbaseClick: () -> Unit = {},
    onMayaClick: () -> Unit = {}
) {
    fun serviceOf(type: ServiceType) = services.find { it.serviceType == type }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MyTheme.Colors.backgroundPrimary)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopNavBase(
                leadingIcon = ImageVector.vectorResource(R.drawable.ic_menu_chevron),
                onLeadingClick = onBackClick,
                centralPart = false,
                trailingPart = false
            )

            TopIntro(
                heading = stringResource(R.string.menu_buy_and_sell_title),
                text = stringResource(R.string.buy_and_sell_subtitle)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Card 1: Uphold + Coinbase
                Menu {
                    serviceOf(ServiceType.UPHOLD)?.let { service ->
                        ServiceItem(
                            service = service,
                            balanceFormat = balanceFormat,
                            onClick = if (service.isAvailable()) onUpholdClick else null
                        )
                    }
                    serviceOf(ServiceType.COINBASE)?.let { service ->
                        ServiceItem(
                            service = service,
                            balanceFormat = balanceFormat,
                            onClick = if (service.isAvailable()) onCoinbaseClick else null
                        )
                    }
                }

                // Card 2: Topper + "Powered by Uphold" strip
                Menu {
                    serviceOf(ServiceType.TOPPER)?.let { service ->
                        ServiceItem(
                            service = service,
                            balanceFormat = balanceFormat,
                            onClick = if (service.isAvailable()) onTopperClick else null
                        )
                    }
                    PoweredByUpholdStrip()
                }

                // Card 3: Maya
                Menu {
                    serviceOf(ServiceType.MAYA)?.let { service ->
                        ServiceItem(
                            service = service,
                            balanceFormat = balanceFormat,
                            onClick = if (service.isAvailable()) onMayaClick else null
                        )
                    }
                }

                if (!hasValidCredentials) {
                    Text(
                        text = stringResource(R.string.services_portal_subtitle_error),
                        style = MyTheme.OverlineCaptionRegular,
                        color = MyTheme.Colors.red,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                }
            }
        }

        if (!isConnected) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 15.dp, vertical = 15.dp)
                    .background(MyTheme.ToastBackground, RoundedCornerShape(10.dp))
                    .padding(vertical = 8.dp, horizontal = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Image(
                    painter = painterResource(ToastImageResource.NoInternet.resourceId),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = stringResource(R.string.no_connection),
                    style = MyTheme.Caption,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun ServiceItem(
    service: BuyAndSellDashServicesModel,
    balanceFormat: MonetaryFormat,
    onClick: (() -> Unit)?
) {
    val subtitle = when (service.serviceStatus) {
        ServiceStatus.IDLE, ServiceStatus.IDLE_DISCONNECTED -> when (service.serviceType) {
            ServiceType.TOPPER -> stringResource(R.string.buy_no_account_needed)
            ServiceType.MAYA -> stringResource(R.string.convert_no_account_needed)
            else -> stringResource(R.string.link_account)
        }
        ServiceStatus.CONNECTED -> stringResource(R.string.connected)
        ServiceStatus.DISCONNECTED -> stringResource(R.string.disconnected)
    }

    val subtitle2 = if (service.serviceStatus == ServiceStatus.DISCONNECTED) {
        stringResource(R.string.last_known_balance)
    } else {
        null
    }

    val dashAmount = if (service.serviceStatus == ServiceStatus.CONNECTED ||
        service.serviceStatus == ServiceStatus.DISCONNECTED
    ) {
        balanceFormat.format(service.balance ?: Coin.ZERO).toString()
    } else {
        null
    }

    val fiatAmount = service.localBalance?.toFormattedString()

    MenuItem(
        title = stringResource(service.serviceType.serviceName),
        subtitle = subtitle,
        subtitle2 = subtitle2,
        icon = service.serviceType.serviceIcon,
        dashAmount = dashAmount,
        fiatAmount = fiatAmount,
        action = onClick
    )
}

@Composable
private fun PoweredByUpholdStrip() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .background(Color(0x1AB0B6BC), RoundedCornerShape(8.dp))
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_uphold),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier
                .width(11.dp)
                .height(16.dp)
        )
        Text(
            text = stringResource(R.string.topper_powered_by),
            style = MyTheme.OverlineCaptionRegular,
            color = MyTheme.Colors.textPrimary,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

@Composable
@Preview
private fun BuyAndSellScreenPreview() {
    BuyAndSellScreenContent(
        services = BuyAndSellDashServicesModel.getBuyAndSellDashServicesList(),
        isConnected = true,
        balanceFormat = MonetaryFormat().noCode(),
        hasValidCredentials = true
    )
}

@Composable
@Preview(name = "No Network")
private fun BuyAndSellScreenNoNetworkPreview() {
    BuyAndSellScreenContent(
        services = BuyAndSellDashServicesModel.getBuyAndSellDashServicesList(),
        isConnected = false,
        balanceFormat = MonetaryFormat().noCode(),
        hasValidCredentials = true
    )
}