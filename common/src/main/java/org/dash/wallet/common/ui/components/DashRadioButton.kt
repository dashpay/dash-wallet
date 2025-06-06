package org.dash.wallet.common.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.dash.wallet.common.R
/**
 * Custom radio button component with text and optional helper text
 * Matches the design system from Figma
 *
 * @param text The main text to display next to the radio button
 * @param helpText Optional help text to display below the main text
 * @param selected Whether this radio button is selected
 * @param onClick Callback to be invoked when this radio button is clicked
 * @param modifier Modifier to be applied to the component
 * @param enabled Whether the radio button is enabled or disabled
 */
@Composable
fun DashRadioButton(
    modifier: Modifier = Modifier,
    text: String,
    helpText: String? = null,
    selected: Boolean,
    onClick: () -> Unit,
    leadingIcon: Int? = null,
    trailingText: String? = null,
    trailingHelpText: String? = null,

    enabled: Boolean = true
) {
    val primaryTextColor = MyTheme.Colors.textPrimary
    val secondaryTextColor = MyTheme.Colors.textSecondary
    val radioButtonColor = MyTheme.Colors.dashBlue
    val borderColor = if (selected) radioButtonColor else Color(0xFFCED2D5)  // #CED2D5 from Figma

    val contentAlpha = if (enabled) 1f else 0.6f

    Surface(
        modifier = modifier
            .selectable(
                selected = selected,
                onClick = onClick,
                enabled = enabled,
                role = Role.RadioButton
            )
            .padding(10.dp, 8.dp),
        color = Color.Transparent
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            //horizontalArrangement = if (textEndAligned) Arrangement.End else Arrangement.Start,
            modifier = Modifier.fillMaxWidth()
        ) {
            leadingIcon?.let {
                Box(
                    modifier = Modifier
                        .size(26.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = it),
                        contentDescription = null,
                        tint = Color.Unspecified
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
            }

            TextContent(
                text = text,
                helpText = helpText,
                primaryTextColor = primaryTextColor.copy(alpha = contentAlpha),
                secondaryTextColor = secondaryTextColor.copy(alpha = contentAlpha),
                textFirst = false,
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(10.dp))
            if (trailingText != null || trailingHelpText != null) {
                TextContent(
                    text = trailingText,
                    helpText = trailingHelpText,
                    textStyle = MyTheme.Caption,
                    primaryTextColor = primaryTextColor.copy(alpha = contentAlpha),
                    secondaryTextColor = secondaryTextColor.copy(alpha = contentAlpha),
                    textFirst = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(10.dp))
            }
            RadioButtonIndicator(
                selected = selected,
                borderColor = borderColor.copy(alpha = contentAlpha),
                radioButtonColor = radioButtonColor.copy(alpha = contentAlpha)
            )
        }
    }
}

@Composable
private fun RadioButtonIndicator(
    selected: Boolean,
    borderColor: Color,
    radioButtonColor: Color
) {
    if (selected) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .border(6.dp, radioButtonColor, CircleShape)
                .padding(5.dp)
        )
    } else {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .border(1.8.dp, borderColor, CircleShape)
                .padding(5.dp)
        )
    }
}

@Composable
private fun TextContent(
    modifier: Modifier = Modifier,
    text: String?,
    helpText: String?,
    textStyle: TextStyle = MyTheme.CaptionMedium,
    helpTextStyle: TextStyle = MyTheme.OverlineCaptionMedium,
    primaryTextColor: Color,
    secondaryTextColor: Color,
    textFirst: Boolean,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = if (textFirst) Alignment.End else Alignment.Start
    ) {
        if (text != null) {
            Text(
                text = text,
                color = primaryTextColor,
                style = textStyle
            )
        }

        if (helpText != null) {
            Text(
                text = helpText,
                color = secondaryTextColor,
                style = helpTextStyle
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun RadioButtonPreview() {
    var selectedOption by remember { mutableIntStateOf(1) }

    Column(modifier = Modifier.padding(16.dp)) {
        DashRadioButton(
            leadingIcon = R.drawable.ic_preview,
            text = "Text",
            helpText = "help text",
            selected = selectedOption == 3,
            onClick = { selectedOption = 3 },
            trailingText = "Text",
            trailingHelpText = "help text"
        )
        // Radio button with text on the right
        DashRadioButton(
            text = "Option 1",
            helpText = "Help text for option 1",
            selected = selectedOption == 0,
            onClick = { selectedOption = 0 }
        )

        Spacer(modifier = Modifier.height(8.dp))

        DashRadioButton(
            text = "Option 2",
            helpText = "Help text for option 2 with leading icon",
            leadingIcon = R.drawable.ic_dash_pay,
            selected = selectedOption == 1,
            onClick = { selectedOption = 1 }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Radio button with text on the right (no help text)
        DashRadioButton(
            text = "Option 3",
            selected = selectedOption == 2,
            onClick = { selectedOption = 2 }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Text on the left, radio button on the right

        HorizontalDivider()
        Text("RadioGroup (simple)", style = MyTheme.OverlineSemibold)
        HorizontalDivider()
        val selectedFrequency = remember { mutableStateOf("Once per month") }
        RadioGroup(
            listOf("Once per week", "Once per month", "Once Per day"),
            selectedFrequency.value,
            { selectedFrequency.value = it }
        )
        HorizontalDivider()
        Text("RadioGroup (local currencies)", style = MyTheme.OverlineSemibold)
        HorizontalDivider()
        val selectedCurrency = remember { mutableIntStateOf(1) }
        RadioGroup(
            listOf(
                DashRadioGroupOption(
                    R.drawable.currency_code_usd,
                    "United States Dollar",
                    helpText = "25.00",
                    trailingHelpText = "USD"
                ),
                DashRadioGroupOption(
                    R.drawable.currency_code_chf,
                    "Swiss Franc",
                    helpText = "22.00",
                    trailingHelpText = "CHF"
                ),
                DashRadioGroupOption(
                    R.drawable.currency_code_rub,
                    "Russian Ruble",
                    helpText = "121.00",
                    trailingHelpText = "RUB"
                )
            ),
            selectedCurrency.intValue,
            { selectedCurrency.intValue = it }
        )
    }
}

data class DashRadioGroupOption(
    val icon: Int? = null,
    val text: String,
    val helpText: String? = null,
    val trailingText: String? = null,
    val trailingHelpText: String? = null
)

// Usage example for a radio group
@Composable
fun RadioGroup(
    options: List<String>,
    selectedOption: String,
    onOptionSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        options.forEach { option ->
            DashRadioButton(
                text = option,
                selected = option == selectedOption,
                onClick = { onOptionSelected(option) },
                modifier = Modifier.fillMaxWidth()
            )

            if (option != options.last()) {
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
fun RadioGroup(
    options: List<DashRadioGroupOption>,
    selectedOption: Int,
    onOptionSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        options.forEachIndexed { index, option ->
            DashRadioButton(
                leadingIcon = option.icon,
                text = option.text,
                helpText = option.helpText,
                trailingText = option.trailingText,
                trailingHelpText = option.trailingHelpText,
                selected = index == selectedOption,
                onClick = { onOptionSelected(index) },
                modifier = Modifier.fillMaxWidth()
            )

            if (option != options.last()) {
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}