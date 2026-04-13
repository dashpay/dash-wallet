package org.dash.wallet.common.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.dash.wallet.common.R

@Composable
fun Menu(
    menuItems: @Composable () -> Unit
) {
    val colors = LocalDashColors.current
    Box(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 20.dp)
            .background(colors.backgroundSecondary, RoundedCornerShape(12.dp)),
    ) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .padding(6.dp)
            .background(colors.backgroundSecondary, RoundedCornerShape(12.dp)),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        menuItems.invoke()
    }
        }
}

@Composable
@Preview
fun MenuPreview() {
    DashWalletTheme {
        val colors = LocalDashColors.current
        Column(
            Modifier.fillMaxWidth()
                .background(colors.backgroundPrimary)
        ) {
            Spacer(Modifier.fillMaxWidth().height(20.dp))
            Menu {
                // With balance display
                MenuItem(
                    title = "Wallet Balance",
                    subtitle = "Available balance",
                    icon = R.drawable.ic_dash_blue_filled,
                    dashAmount = "0.00",
                    fiatAmount = "0.00 US$"
                )

                // With trailing button
                MenuItem(
                    title = "More Action Item",
                    icon = R.drawable.ic_dash_blue_filled,
                    onTrailingButtonClick = { },
                    showChevron = true
                )
            }
            Spacer(Modifier.fillMaxWidth().height(20.dp))
        }
    }
}