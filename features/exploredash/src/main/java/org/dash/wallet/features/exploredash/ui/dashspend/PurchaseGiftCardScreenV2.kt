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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.MyImages
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.Size
import org.dash.wallet.common.ui.components.Style
import org.dash.wallet.common.ui.components.TopIntroSend
import org.dash.wallet.common.ui.components.TopNavBase
import org.dash.wallet.common.ui.enter_amount.NumericKeyboardCompose
import org.dash.wallet.common.ui.segmented_picker.SegmentedOption
import org.dash.wallet.common.ui.segmented_picker.SegmentedPicker
import org.dash.wallet.common.ui.segmented_picker.SegmentedPickerStyle
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
    val errorText: String = "",
    val discountHintText: String = "",
    val allowedQuantities: Map<Double, Int>
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
            trailingIcon = MyImages.NavBarInfo,
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
                            denominations = mode.denominations,
                            denominationQuantities = uiState.denominationQuantities,
                            totalAmountText = uiState.totalAmountText,
                            onTabChanged = onTabChanged,
                            onQuantityChanged = onQuantityChanged
                        )
                    }
                    is GiftCardPurchaseMode.Fixed -> {
                        FixedContent(
                            denominations = mode.denominations,
                            denominationQuantities = uiState.denominationQuantities,
                            totalAmountText = uiState.totalAmountText,
                            onQuantityChanged = onQuantityChanged
                        )
                    }
                }
            }

            // Min/max limits + keyboard pinned above the button for the flexible-single mode
            if (uiState.mode is GiftCardPurchaseMode.FlexibleSingle) {
                PurchaseLimitsErrorDiscountHint(
                    minHintText = uiState.minHintText,
                    maxHintText = uiState.maxHintText,
                    errorText = uiState.errorText,
                    discountHintText = uiState.discountHintText
                )
                Spacer(modifier = Modifier.height(16.dp))
                NumericKeyboardCompose(
                    modifier = Modifier.fillMaxWidth(),
                    onKeyInput = onKeyInput
                )
            } else {
                PurchaseLimitsErrorDiscountHint(
                    minHintText = "",
                    maxHintText = "",
                    errorText = uiState.errorText,
                    discountHintText = uiState.discountHintText
                )
            }

            // Single continue button pinned outside the scroll area
            Spacer(modifier = Modifier.height(16.dp))
            DashButton(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(org.dash.wallet.common.R.string.button_continue),
                style = Style.FilledBlue,
                size = Size.Large,
                isEnabled = uiState.canContinue
            )
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun PurchaseLimitsErrorDiscountHint(
    minHintText: String,
    maxHintText: String,
    errorText: String,
    discountHintText: String,
    modifier: Modifier = Modifier
) {
    if (minHintText.isEmpty() && maxHintText.isEmpty() && errorText.isEmpty() && discountHintText.isEmpty()) return

    // Detect which bound is exceeded so we can colour it red independently.
    val minError = errorText.isNotEmpty() && errorText == minHintText
    val maxError = errorText.isNotEmpty() && errorText == maxHintText

    if (minHintText.isNotEmpty() && maxHintText.isNotEmpty()) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = minHintText,
                style = MyTheme.Caption,
                color = if (minError) MyTheme.Colors.red else MyTheme.Colors.textPrimary
            )
            Text(
                text = maxHintText,
                style = MyTheme.Caption,
                color = if (maxError) MyTheme.Colors.red else MyTheme.Colors.textPrimary
            )
        }
    }

    // Non-range errors (e.g. blockchain replaying) shown above the min/max row.
    Spacer(modifier = Modifier.height(30.dp))
    if (errorText.isNotEmpty() && !minError && !maxError) {
        Text(
            text = errorText,
            style = MyTheme.Caption,
            color = MyTheme.Colors.red,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Discount explanation, shown when amount is valid and within limits
    if (discountHintText.isNotEmpty() && errorText.isEmpty()) {
        // Spacer(modifier = Modifier.height(30.dp))
        Text(
            text = discountHintText,
            textAlign = TextAlign.Center,
            style = MyTheme.Caption,
            color = MyTheme.Colors.textPrimary,
            modifier = Modifier.fillMaxWidth()
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
        style = MyTheme.Typography.HeadlineLargeMedium,
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
        val tabOptions = listOf(
            SegmentedOption(stringResource(R.string.gift_card_tab_single)),
            SegmentedOption(stringResource(R.string.gift_card_tab_multiple))
        )
        SegmentedPicker(
            options = tabOptions,
            selectedIndex = 0,
            onOptionSelected = { _, index -> onTabChanged(index == 1) },
            style = SegmentedPickerStyle(cornerRadius = 20f),
            modifier = Modifier.fillMaxWidth().height(42.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        AmountDisplay(
            currencySymbol = uiState.currencySymbol,
            amountText = uiState.amountText,
            modifier = Modifier.fillMaxWidth()
        )

    }
}

@Composable
private fun FlexibleMultipleContent(
    denominations: List<Double>,
    denominationQuantities: Map<Double, Int>,
    totalAmountText: String,
    onTabChanged: (isMultiple: Boolean) -> Unit,
    onQuantityChanged: (denomination: Double, quantity: Int) -> Unit
) {
    Column {
        val tabOptions = listOf(
            SegmentedOption(stringResource(R.string.gift_card_tab_single)),
            SegmentedOption(stringResource(R.string.gift_card_tab_multiple))
        )
        SegmentedPicker(
            options = tabOptions,
            selectedIndex = 1,
            onOptionSelected = { _, index -> onTabChanged(index == 1) },
            style = SegmentedPickerStyle(cornerRadius = 20f),
            modifier = Modifier.fillMaxWidth().height(42.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        TotalAmountDisplay(
            totalAmountText = totalAmountText,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        DenominationList(
            allowMultipleDenominations = true,
            denominations = denominations,
            denominationQuantities = denominationQuantities,
            onQuantityChanged = onQuantityChanged
        )
    }
}

@Composable
private fun FixedContent(
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
            allowMultipleDenominations = false,
            denominations = denominations,
            denominationQuantities = denominationQuantities,
            onQuantityChanged = onQuantityChanged
        )
    }
}

@Composable
private fun DenominationList(
    allowMultipleDenominations: Boolean,
    denominations: List<Double>,
    denominationQuantities: Map<Double, Int>,
    onQuantityChanged: (denomination: Double, quantity: Int) -> Unit
) {
    val currencyFormat = NumberFormat.getCurrencyInstance().apply {
        currency = Currency.getInstance("USD")
        minimumFractionDigits = 0
    }

    val hasAnySelection = denominationQuantities.values.any { it > 0 }

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
            val quantity = denominationQuantities[denomination] ?: 0
            val increaseEnabled = allowMultipleDenominations || !hasAnySelection || quantity > 0

            DenominationRow(
                label = currencyFormat.format(denomination),
                quantity = quantity,
                increaseEnabled = increaseEnabled,
                onDecrease = {
                    if (quantity > 0) onQuantityChanged(denomination, quantity - 1)
                },
                onIncrease = {
                    onQuantityChanged(denomination, quantity + 1)
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
    increaseEnabled: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    val decreaseEnabled = quantity > 0
    val quantityColor = if (increaseEnabled || quantity > 0) MyTheme.Colors.textPrimary else MyTheme.Colors.gray

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_gift_card_icon),
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
                enabled = decreaseEnabled
            ) {
                Text(
                    text = "−",
                    style = MyTheme.Typography.TitleMediumSemibold,
                    color = if (decreaseEnabled) MyTheme.Colors.textPrimary else MyTheme.Colors.gray
                )
            }

            Text(
                text = quantity.toString(),
                style = MyTheme.Typography.TitleMediumSemibold,
                color = quantityColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(24.dp)
            )

            CircleButton(
                onClick = onIncrease,
                enabled = increaseEnabled
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = if (increaseEnabled) MyTheme.Colors.textPrimary else MyTheme.Colors.gray,
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
            allowedQuantities = mapOf(),
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
            allowedQuantities = mapOf(5.0 to 6, 10.0 to 1, 20.0 to 1),
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
            allowedQuantities = mapOf(15.0 to 1),
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