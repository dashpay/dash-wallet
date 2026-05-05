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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.EnterAmount
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
import org.dash.wallet.common.util.Constants
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
    onReset: () -> Unit,
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
            // trailingIcon = MyImages.NavBarInfo,
            // trailingIconCircle = false,
            // onTrailingClick = onInfo
        )
        Column(modifier = Modifier.fillMaxSize()
            .padding(top = 10.dp)) {
            Spacer(modifier = Modifier.height(64.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 20.dp)
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

                Spacer(modifier = Modifier.height(20.dp))

                when (val mode = uiState.mode) {
                    is GiftCardPurchaseMode.FlexibleSingle -> {
                        FlexibleSingleContent(
                            uiState = uiState,
                            onTabChanged = onTabChanged
                        )
                        // Per Figma: error/discount hint sits directly under the amount,
                        // not pinned above the keyboard, so it scrolls with the top section.
                        PurchaseLimitsErrorDiscountHint(
                            minHintText = uiState.minHintText,
                            maxHintText = uiState.maxHintText,
                            errorText = uiState.errorText,
                            discountHintText = uiState.discountHintText
                        )
                    }
                    is GiftCardPurchaseMode.FlexibleMultiple -> {
                        FlexibleMultipleContent(
                            denominations = mode.denominations,
                            denominationQuantities = uiState.denominationQuantities,
                            totalAmountText = uiState.totalAmountText,
                            onTabChanged = onTabChanged,
                            onQuantityChanged = onQuantityChanged,
                            onReset = onReset
                        )
                    }
                    is GiftCardPurchaseMode.Fixed -> {
                        FixedContent(
                            denominations = mode.denominations,
                            denominationQuantities = uiState.denominationQuantities,
                            totalAmountText = uiState.totalAmountText,
                            onQuantityChanged = onQuantityChanged,
                            onReset = onReset
                        )
                    }
                }
            }

            // Bottom area: FlexibleSingle uses a single rounded-top panel that contains
            // both the numeric keyboard and the Continue button, flush with the screen edges.
            // Other modes keep the inset hint + button layout.
            if (uiState.mode is GiftCardPurchaseMode.FlexibleSingle) {
                NumericKeyboardCompose(
                    modifier = Modifier.fillMaxWidth(),
                    onKeyInput = onKeyInput,
                    bottomSlot = {
                        Spacer(modifier = Modifier.height(8.dp))
                        DashButton(
                            onClick = onContinue,
                            modifier = Modifier.fillMaxWidth(),
                            text = stringResource(org.dash.wallet.common.R.string.button_continue),
                            style = Style.FilledBlue,
                            size = Size.Large,
                            isEnabled = uiState.canContinue
                        )
                    }
                )
            } else {
                // Per Figma node 3118:45588 the bottom keyboard panel has 20px padding all sides
                // and a 20px gap between the hint text and the Continue button.
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    PurchaseLimitsErrorDiscountHint(
                        minHintText = "",
                        maxHintText = "",
                        errorText = uiState.errorText,
                        discountHintText = uiState.discountHintText
                    )
                    Spacer(modifier = Modifier.height(20.dp))
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

    val hasMinMaxRow = minHintText.isNotEmpty() && maxHintText.isNotEmpty()
    val hasCenteredError = errorText.isNotEmpty() && !minError && !maxError
    val hasDiscountText = discountHintText.isNotEmpty() && errorText.isEmpty()

    // Per Figma (helpWrap): Body M Regular = 14sp/20 line height; centered when only error/discount,
    // SpaceBetween for min/max row.
    if (hasMinMaxRow) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = minHintText,
                style = MyTheme.Typography.BodyMedium,
                color = if (minError) MyTheme.Colors.red else MyTheme.Colors.textSecondary
            )
            Text(
                text = maxHintText,
                style = MyTheme.Typography.BodyMedium,
                color = if (maxError) MyTheme.Colors.red else MyTheme.Colors.textSecondary
            )
        }
    }

    // Non-range errors (e.g. blockchain replaying or "Insufficient funds") shown centered.
    if (hasCenteredError) {
        if (hasMinMaxRow) Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = errorText,
            style = MyTheme.Typography.BodyMedium,
            color = MyTheme.Colors.red,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Discount explanation, shown when amount is valid and within limits.
    if (hasDiscountText) {
        if (hasMinMaxRow) Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = discountHintText,
            textAlign = TextAlign.Center,
            style = MyTheme.Typography.BodyMedium,
            color = MyTheme.Colors.textPrimary,
            modifier = Modifier.fillMaxWidth()
        )
    }
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
            modifier = Modifier.fillMaxWidth().height(40.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        EnterAmount(
            primaryAmount = uiState.amountText,
            currencyCodes = listOf("USD"),
            selectedCurrencyIndex = 0,
            showMaxButton = false,
            showBalanceButton = false,
            showSecondary = false,
            showPrimaryChevron = false,
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
    onQuantityChanged: (denomination: Double, quantity: Int) -> Unit,
    onReset: () -> Unit
) {
    Column {
        val tabOptions = listOf(
            SegmentedOption(stringResource(R.string.gift_card_tab_single)),
            SegmentedOption(stringResource(R.string.gift_card_tab_multiple))
        )
        // Per Figma node 3118:45513 the SegmentedControl is 40px tall (matches Single mode).
        SegmentedPicker(
            options = tabOptions,
            selectedIndex = 1,
            onOptionSelected = { _, index -> onTabChanged(index == 1) },
            style = SegmentedPickerStyle(cornerRadius = 20f),
            modifier = Modifier.fillMaxWidth().height(40.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        EnterAmount(
            primaryAmount = totalAmountText,
            currencyCodes = listOf(Constants.USD_CURRENCY),
            selectedCurrencyIndex = 0,
            showMaxButton = false,
            showBalanceButton = false,
            showSecondary = false,
            showPrimaryChevron = false,
            modifier = Modifier.fillMaxWidth()
        )

        // Per Figma the gap between EnterAmount and the denomination card is the parent
        // 20px column gap (not 24).
        Spacer(modifier = Modifier.height(20.dp))

        DenominationList(
            allowMultipleDenominations = true,
            denominations = denominations,
            denominationQuantities = denominationQuantities,
            onQuantityChanged = onQuantityChanged,
            onReset = onReset
        )
    }
}

@Composable
private fun FixedContent(
    denominations: List<Double>,
    denominationQuantities: Map<Double, Int>,
    totalAmountText: String,
    onQuantityChanged: (denomination: Double, quantity: Int) -> Unit,
    onReset: () -> Unit
) {
    // Per Figma node 3115:44204 the Fixed-mode layout has no leading spacer:
    // EnterAmount sits directly under TopIntroSend (20dp gap controlled by the
    // parent column), then a 20dp gap before the DenominationList card —
    // identical to FlexibleMultiple's spacing.
    Column {
        EnterAmount(
            primaryAmount = totalAmountText,
            currencyCodes = listOf(Constants.USD_CURRENCY),
            selectedCurrencyIndex = 0,
            showMaxButton = false,
            showBalanceButton = false,
            showSecondary = false,
            showPrimaryChevron = false,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(20.dp))

        DenominationList(
            allowMultipleDenominations = false,
            denominations = denominations,
            denominationQuantities = denominationQuantities,
            onQuantityChanged = onQuantityChanged,
            onReset = onReset
        )
    }
}

@Composable
private fun DenominationList(
    allowMultipleDenominations: Boolean,
    denominations: List<Double>,
    denominationQuantities: Map<Double, Int>,
    onQuantityChanged: (denomination: Double, quantity: Int) -> Unit,
    onReset: () -> Unit
) {
    val currencyFormat = NumberFormat.getCurrencyInstance().apply {
        currency = Currency.getInstance(Constants.USD_CURRENCY)
        minimumFractionDigits = 0
    }

    val hasAnySelection = denominationQuantities.values.any { it > 0 }

    // Per Figma node 3135:57658 — white card, 20px radius, 20px padding all sides,
    // 20px gap between the rows-block and the Reset button. Inside the rows-block,
    // a 10px gap between rows (no dividers).
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MyTheme.Colors.backgroundSecondary,
                shape = RoundedCornerShape(20.dp)
            )
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            denominations.forEach { denomination ->
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
            }
        }

        if (hasAnySelection) {
            DashButton(
                text = stringResource(R.string.purchase_gift_card_reset),
                style = Style.TintedGray,
                size = Size.ExtraSmall,
                stretch = false,
                onClick = onReset,
                modifier = Modifier
                    .align(Alignment.End)
                    .width(126.dp)
            )
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

    // Per Figma node 3118:45517 the row has natural height (icon 26h, stepper 34h with 4px touch
    // padding = 42 total). Card padding (20dp) + 10dp inter-row gaps already control spacing.
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_gift_card_icon),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size(width = 40.dp, height = 26.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = label,
            // Body/Body L Medium per Figma (16sp / 24 line-height / Medium 500).
            style = MyTheme.Typography.BodyLargeMedium,
            color = MyTheme.Colors.textPrimary,
            modifier = Modifier.weight(1f)
        )

        // Stepper: per Figma each touchArea has 4px padding around a 34px circle, with a 6px
        // gap between (touchArea | qty | touchArea).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CircleButton(
                onClick = onDecrease,
                enabled = decreaseEnabled
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_stepper_minus),
                    contentDescription = null,
                    tint = if (decreaseEnabled) MyTheme.Colors.textPrimary else MyTheme.Colors.gray,
                    modifier = Modifier.size(11.dp)
                )
            }

            Text(
                text = quantity.toString(),
                // Per Figma: subtitle1-semibold (16sp / 22 / SemiBold), 30px width, centered.
                style = MyTheme.SubtitleSemibold,
                color = quantityColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(30.dp)
            )

            CircleButton(
                onClick = onIncrease,
                enabled = increaseEnabled
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_stepper_plus),
                    contentDescription = null,
                    tint = if (increaseEnabled) MyTheme.Colors.textPrimary else MyTheme.Colors.gray,
                    modifier = Modifier.size(11.dp)
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
    // Per Figma: 34dp visible circle (1.5dp stroke) wrapped in a 42dp touch area
    // (4dp padding on each side). `enabled` uses primary5 (5% black), disabled uses primary4 (8%).
    Box(
        modifier = modifier
            .size(42.dp)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .border(
                    width = 1.5.dp,
                    color = if (enabled) MyTheme.Colors.primary5 else MyTheme.Colors.primary4,
                    shape = CircleShape
                )
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
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
            totalAmountText = "0.00",
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
        onReset = {},
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
            totalAmountText = "40.00",
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
        onReset = {},
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
            totalAmountText = "25.00",
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
        onReset = {},
        onContinue = {}
    )
}