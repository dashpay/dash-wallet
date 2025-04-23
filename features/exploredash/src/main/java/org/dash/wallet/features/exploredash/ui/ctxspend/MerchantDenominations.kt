package org.dash.wallet.features.exploredash.ui.ctxspend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.dash.wallet.common.ui.components.ButtonLarge
import org.dash.wallet.common.ui.components.ButtonStyles
import org.dash.wallet.common.ui.components.MyTheme
import java.text.NumberFormat
import java.util.Currency
import org.dash.wallet.features.exploredash.R

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MerchantDenominations(
    modifier: Modifier = Modifier,
    denominations: List<Int>, 
    currency: Currency,
    selectedDenomination: Int? = null,
    onDenominationSelected: (Int) -> Unit = {},
    onContinue: () -> Unit = {}
) {
    val numberFormat = remember { NumberFormat.getCurrencyInstance().apply {
        this.currency = currency
        minimumFractionDigits = 0
    } }

    Column(modifier = modifier) {
        Text(
            stringResource(R.string.select_amount),
            style = MyTheme.H6Bold
        )
        Text(
            stringResource(R.string.select_fixed_amount),
            style = MyTheme.Body2Regular,
            color = MyTheme.Colors.textSecondary,
            modifier = Modifier.padding(top = 4.dp)
        )

        FlowRow(
            modifier = Modifier
                .padding(top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            denominations.forEach { denomination ->
                FilterChip(
                    modifier = Modifier.height(54.dp).width(76.dp),
                    selected = denomination == selectedDenomination,
                    onClick = { onDenominationSelected(denomination) },
                    label = {
                        Text(
                            text = numberFormat.format(denomination),
                            textAlign = TextAlign.Center,
                            style = MyTheme.SubtitleSemibold,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MyTheme.Colors.dashBlue5,
                        containerColor = Color.Transparent,
                        selectedLabelColor = MyTheme.Colors.textPrimary,
                        labelColor = MyTheme.Colors.textPrimary
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        borderColor = MyTheme.Colors.primary4,
                        selectedBorderColor = MyTheme.Colors.dashBlue,
                        borderWidth = 1.5.dp,
                        selectedBorderWidth = 1.5.dp,
                        enabled = true,
                        selected = denomination == selectedDenomination
                    ),
                    shape = RoundedCornerShape(14.dp)
                )
            }
        }

        ButtonLarge(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
            colors = ButtonStyles.blueWithWhiteText(),
            textId = R.string.button_continue,
            enabled = selectedDenomination != null
        )
    }
}

@Preview(showBackground = true)
@Composable
fun MerchantDenominationsPreview() {
    val denominations = listOf(5, 10, 15, 20, 50, 75, 100, 200)
    val currency = Currency.getInstance("USD")
    
    MerchantDenominations(
        modifier = Modifier.padding(20.dp).width(300.dp),
        denominations = denominations,
        currency = currency,
        selectedDenomination = 20
    )
}