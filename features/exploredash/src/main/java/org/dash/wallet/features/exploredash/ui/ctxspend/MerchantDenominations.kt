package org.dash.wallet.features.exploredash.ui.ctxspend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.dash.wallet.common.ui.components.Colors
import org.dash.wallet.common.ui.components.MyTheme
import java.text.NumberFormat
import java.util.Currency

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MerchantDenominations(
    modifier: Modifier = Modifier,
    denominations: List<Int>, 
    currency: Currency,
    selectedDenomination: Int? = null,
    onDenominationSelected: (Int) -> Unit = {}
) {
    val numberFormat = remember { NumberFormat.getCurrencyInstance().apply {
        this.currency = currency
        minimumFractionDigits = 0
    } }
    
    FlowRow(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp),
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
                        style = MyTheme.subtitleSemibold,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Colors.dashBlue5,
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    selectedLabelColor = Colors.textPrimary,
                    labelColor = Colors.textPrimary
                ),
                border = FilterChipDefaults.filterChipBorder(
                    borderColor = Colors.border,
                    selectedBorderColor = Colors.dashBlue,
                    borderWidth = 1.5.dp,
                    selectedBorderWidth = 1.5.dp,
                    enabled = true,
                    selected = denomination == selectedDenomination
                )
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MerchantDenominationsPreview() {
    val denominations = listOf(5, 10, 15, 20, 50, 75, 100, 200)
    val currency = Currency.getInstance("USD")
    
    MerchantDenominations(
        modifier = Modifier.width(300.dp),
        denominations = denominations,
        currency = currency,
        selectedDenomination = 20
    )
}