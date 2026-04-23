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

package org.dash.wallet.features.exploredash.ui.dashspend

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import org.dash.wallet.common.ui.components.ButtonLarge
import org.dash.wallet.common.ui.components.ButtonStyles
import org.dash.wallet.common.ui.components.MyImages
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.TopIntroSend
import org.dash.wallet.common.ui.components.TopNavBase
import org.dash.wallet.common.ui.enter_amount.NumericKeyboardCompose
import org.dash.wallet.features.exploredash.R
import java.text.NumberFormat
import java.util.Currency

sealed class GiftCardPurchaseMode {
    object FlexibleSingle : GiftCardPurchaseMode()
    data class FlexibleMultiple(val denominations: List<Double>) : GiftCardPurchaseMode()
    data class Fixed(val denominations: List<Double>) : GiftCardPurchaseMode()
}

data class PurchaseGiftCardV2UiState(
    val mode: GiftCardPurchaseMode = GiftCardPurchaseMode.FlexibleSingle,
    val merchantName: String = "",
    val merchantLogoUrl: String? = null,
    val dashBalance: String = "",
    val fiatBalance: String = "",
    val showBalance: Boolean = false,
    val currencySymbol: String = "$",
    val amountText: String = "0",
    val minHintText: String = "",
    val maxHintText: String = "",
    val denominationQuantities: Map<Double, Int> = emptyMap(),
    val totalAmountText: String = "$0.00",
    val canContinue: Boolean = false,
    val errorText: String = ""
)

@Composable
fun PurchaseGiftCardScreenV2(
    uiState: PurchaseGiftCardV2UiState,
    onBack: () -> Unit,
    onInfo: () -> Unit,
    onTabChanged: (isMultiple: Boolean) -> Unit,
    onToggleBalance: () -> Unit,
    onKeyInput: (String) -> Unit,
    onQuantityChanged: (denomination: Double, quantity: Int) -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MyTheme.Colors.backgroundPrimary)
    ) {
        TopNavBase(
            modifier = Modifier.align(Alignment.TopCenter),
            leadingIcon = MyImages.MenuChevron,
            onLeadingClick = onBack,
            centralPart = false,
            trailingIcon = Icons.Default.Info,
            trailingIconCircle = false,
            onTrailingClick = onInfo
        )
        Column(modifier = Modifier.fillMaxSize()
            .padding(horizontal = 20.dp)
            .padding(top = 10.dp)) {
            Spacer(modifier = Modifier.height(64.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                TopIntroSend(
                    heading = stringResource(R.string.gift_card_buy_title),
                    balanceVisible = uiState.showBalance,
                    onToggleVisibility = onToggleBalance,
                    dashBalance = uiState.dashBalance,
                    preposition = stringResource(org.dash.wallet.common.R.string.preposition_at),
                    toIconUrl = uiState.merchantLogoUrl,
                    toName = uiState.merchantName,
                    fiatBalance = uiState.fiatBalance,
                    modifier = Modifier,
                )

                Spacer(modifier = Modifier.height(16.dp))

                when (val mode = uiState.mode) {
                    is GiftCardPurchaseMode.FlexibleSingle -> {
                        FlexibleSingleContent(
                            uiState = uiState,
                            onTabChanged = onTabChanged
                        )
                    }
                    is GiftCardPurchaseMode.FlexibleMultiple -> {
                        FlexibleMultipleContent(
                            uiState = uiState,
                            denominations = mode.denominations,
                            denominationQuantities = uiState.denominationQuantities,
                            totalAmountText = uiState.totalAmountText,
                            onTabChanged = onTabChanged,
                            onQuantityChanged = onQuantityChanged
                        )
                    }
                    is GiftCardPurchaseMode.Fixed -> {
                        FixedContent(
                            uiState = uiState,
                            denominations = mode.denominations,
                            denominationQuantities = uiState.denominationQuantities,
                            totalAmountText = uiState.totalAmountText,
                            onQuantityChanged = onQuantityChanged
                        )
                    }
                }
            }

            // Keyboard pinned above the button for the flexible-single mode
            if (uiState.mode is GiftCardPurchaseMode.FlexibleSingle) {
                Spacer(modifier = Modifier.height(24.dp))
                NumericKeyboardCompose(
                    modifier = Modifier.fillMaxWidth(),
                    onKeyInput = onKeyInput
                )
            }

            // Single continue button pinned outside the scroll area
            Spacer(modifier = Modifier.height(16.dp))
            ButtonLarge(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonStyles.blueWithWhiteText(),
                textId = org.dash.wallet.common.R.string.button_continue,
                enabled = uiState.canContinue
            )
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun GiftCardSegmentedControl(
    isMultipleSelected: Boolean,
    onTabChanged: (isMultiple: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .background(
                color = MyTheme.Colors.primary5,
                shape = RoundedCornerShape(10.dp)
            )
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SegmentedTab(
            text = stringResource(R.string.gift_card_tab_single),
            selected = !isMultipleSelected,
            onClick = { onTabChanged(false) },
            modifier = Modifier.weight(1f)
        )
        SegmentedTab(
            text = stringResource(R.string.gift_card_tab_multiple),
            selected = isMultipleSelected,
            onClick = { onTabChanged(true) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SegmentedTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(34.dp)
            .background(
                color = if (selected) MyTheme.Colors.backgroundSecondary else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MyTheme.OverlineMedium,
            color = if (selected) MyTheme.Colors.textPrimary else MyTheme.Colors.textSecondary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun AmountDisplay(
    currencySymbol: String = "$",
    amountText: String,
    modifier: Modifier = Modifier
) {
    val annotated = buildAnnotatedString {
        append(currencySymbol)
        append(amountText)
    }
    Text(
        text = annotated,
        style = MyTheme.Typography.DisplaySmallMedium,
        color = MyTheme.Colors.textPrimary,
        textAlign = TextAlign.Center,
        modifier = modifier
    )
}

@Composable
private fun TotalAmountDisplay(
    totalAmountText: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = totalAmountText,
        style = MyTheme.Typography.HeadlineLargeMedium,
        color = MyTheme.Colors.textPrimary,
        textAlign = TextAlign.Center,
        modifier = modifier
    )
}

@Composable
private fun FlexibleSingleContent(
    uiState: PurchaseGiftCardV2UiState,
    onTabChanged: (isMultiple: Boolean) -> Unit
) {
    Column {
        GiftCardSegmentedControl(
            isMultipleSelected = false,
            onTabChanged = onTabChanged
        )

        Spacer(modifier = Modifier.height(24.dp))

        AmountDisplay(
            currencySymbol = uiState.currencySymbol,
            amountText = uiState.amountText,
            modifier = Modifier.fillMaxWidth()
        )

        if (uiState.errorText.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = uiState.errorText,
                style = MyTheme.Caption,
                color = MyTheme.Colors.red,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        } else if (uiState.minHintText.isNotEmpty() || uiState.maxHintText.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = uiState.minHintText,
                    style = MyTheme.Caption,
                    color = MyTheme.Colors.textSecondary
                )
                Text(
                    text = uiState.maxHintText,
                    style = MyTheme.Caption,
                    color = MyTheme.Colors.textSecondary
                )
            }
        }
    }
}

@Composable
private fun FlexibleMultipleContent(
    uiState: PurchaseGiftCardV2UiState,
    denominations: List<Double>,
    denominationQuantities: Map<Double, Int>,
    totalAmountText: String,
    onTabChanged: (isMultiple: Boolean) -> Unit,
    onQuantityChanged: (denomination: Double, quantity: Int) -> Unit
) {
    Column {
        GiftCardSegmentedControl(
            isMultipleSelected = true,
            onTabChanged = onTabChanged
        )

        Spacer(modifier = Modifier.height(24.dp))

        TotalAmountDisplay(
            totalAmountText = totalAmountText,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        DenominationList(
            denominations = denominations,
            denominationQuantities = denominationQuantities,
            onQuantityChanged = onQuantityChanged
        )

        if (uiState.errorText.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = uiState.errorText,
                style = MyTheme.Caption,
                color = MyTheme.Colors.red,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()//.padding(horizontal = 20.dp)
            )
        }

    }
}

@Composable
private fun FixedContent(
    uiState: PurchaseGiftCardV2UiState,
    denominations: List<Double>,
    denominationQuantities: Map<Double, Int>,
    totalAmountText: String,
    onQuantityChanged: (denomination: Double, quantity: Int) -> Unit
) {
    Column {
        Spacer(modifier = Modifier.height(8.dp))

        TotalAmountDisplay(
            totalAmountText = totalAmountText,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        DenominationList(
            denominations = denominations,
            denominationQuantities = denominationQuantities,
            onQuantityChanged = onQuantityChanged
        )
        if (uiState.errorText.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = uiState.errorText,
                style = MyTheme.Caption,
                color = MyTheme.Colors.red,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()//.padding(horizontal = 20.dp)
            )
        }

    }
}

@Composable
private fun DenominationList(
    denominations: List<Double>,
    denominationQuantities: Map<Double, Int>,
    onQuantityChanged: (denomination: Double, quantity: Int) -> Unit
) {
    val currencyFormat = NumberFormat.getCurrencyInstance().apply {
        currency = Currency.getInstance("USD")
        minimumFractionDigits = 0
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MyTheme.Colors.backgroundSecondary,
                shape = RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 20.dp)
    ) {
        denominations.forEachIndexed { index, denomination ->
            DenominationRow(
                label = currencyFormat.format(denomination),
                quantity = denominationQuantities[denomination] ?: 0,
                onDecrease = {
                    val current = denominationQuantities[denomination] ?: 0
                    if (current > 0) onQuantityChanged(denomination, current - 1)
                },
                onIncrease = {
                    val current = denominationQuantities[denomination] ?: 0
                    onQuantityChanged(denomination, current + 1)
                }
            )
            if (index < denominations.lastIndex) {
                HorizontalDivider(
                    color = MyTheme.Colors.divider,
                    thickness = 1.dp
                )
            }
        }
    }
}

@Composable
private fun DenominationRow(
    label: String,
    quantity: Int,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_gift_card),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size(40.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = label,
            style = MyTheme.Typography.TitleMediumSemibold,
            color = MyTheme.Colors.textPrimary,
            modifier = Modifier.weight(1f)
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircleButton(
                onClick = onDecrease,
                enabled = quantity > 0
            ) {
                Text(
                    text = "−",
                    style = MyTheme.Typography.TitleMediumSemibold,
                    color = if (quantity > 0) MyTheme.Colors.textPrimary else MyTheme.Colors.gray
                )
            }

            Text(
                text = quantity.toString(),
                style = MyTheme.Typography.TitleMediumSemibold,
                color = MyTheme.Colors.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(24.dp)
            )

            CircleButton(
                onClick = onIncrease,
                enabled = true
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = MyTheme.Colors.textPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun CircleButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .size(34.dp)
            .border(
                width = 1.dp,
                color = if (enabled) MyTheme.Colors.primary4 else MyTheme.Colors.primary5,
                shape = CircleShape
            )
            .clip(CircleShape)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "Flexible – Single")
@Composable
private fun PreviewFlexibleSingle() {
    PurchaseGiftCardScreenV2(
        uiState = PurchaseGiftCardV2UiState(
            mode = GiftCardPurchaseMode.FlexibleSingle,
            merchantName = "Amazon",
            merchantLogoUrl = null,
            dashBalance = "0.4812",
            fiatBalance = "$29.50",
            showBalance = true,
            amountText = "25",
            minHintText = "Min: $5.00",
            maxHintText = "Max: $200.00",
            totalAmountText = "$0.00",
            canContinue = true,
            errorText = ""
        ),
        onBack = {},
        onInfo = {},
        onTabChanged = {},
        onToggleBalance = {},
        onKeyInput = {},
        onQuantityChanged = { _, _ -> },
        onContinue = {}
    )
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "Flexible – Multiple")
@Composable
private fun PreviewFlexibleMultiple() {
    PurchaseGiftCardScreenV2(
        uiState = PurchaseGiftCardV2UiState(
            mode = GiftCardPurchaseMode.FlexibleMultiple(
                denominations = listOf(5.0, 10.0, 20.0, 50.0, 200.0)
            ),
            merchantName = "Amazon",
            merchantLogoUrl = null,
            dashBalance = "0.4812",
            fiatBalance = "$29.50",
            showBalance = true,
            amountText = "0",
            denominationQuantities = mapOf(10.0 to 2, 20.0 to 1),
            totalAmountText = "$40.00",
            canContinue = true,
            errorText = ""
        ),
        onBack = {},
        onInfo = {},
        onTabChanged = {},
        onToggleBalance = {},
        onKeyInput = {},
        onQuantityChanged = { _, _ -> },
        onContinue = {}
    )
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "Fixed Denominations")
@Composable
private fun PreviewFixed() {
    PurchaseGiftCardScreenV2(
        uiState = PurchaseGiftCardV2UiState(
            mode = GiftCardPurchaseMode.Fixed(
                denominations = listOf(15.0, 25.0, 50.0, 100.0)
            ),
            merchantName = "Target",
            merchantLogoUrl = null,
            dashBalance = "0.4812",
            fiatBalance = "$29.50",
            showBalance = false,
            amountText = "0",
            denominationQuantities = mapOf(25.0 to 1),
            totalAmountText = "$25.00",
            canContinue = true,
            errorText = ""
        ),
        onBack = {},
        onInfo = {},
        onTabChanged = {},
        onToggleBalance = {},
        onKeyInput = {},
        onQuantityChanged = { _, _ -> },
        onContinue = {}
    )
}