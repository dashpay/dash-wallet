package org.dash.wallet.common.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.dash.wallet.common.R

@Composable
fun DashCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    title: String? = null,
    subtitle: String? = null,
    leadingIcon: Int? = null,
    trailingText: String? = null,
    trailingHelpText: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val textPrimary = MyTheme.Colors.textPrimary
    val textSecondary = MyTheme.Colors.textSecondary

    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 50.dp)
            //.clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled
            ) {
                onCheckedChange(!checked)
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Leading content area
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Leading icon if present
            leadingIcon?.let {
                Box(
                    modifier = Modifier
                        .size(26.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = it),
                        contentDescription = null,
                        tint = Color.Unspecified,
                    )
                }
            }

            // Leading text content
            if (title != null || subtitle != null) {
                Column(
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    title?.let {
                        Text(
                            text = it,
                            color = if (enabled) textPrimary else textSecondary,
                            style = MyTheme.CaptionMedium
                        )
                    }
                    subtitle?.let {
                        Text(
                            text = it,
                            color = MyTheme.Colors.darkGray,
                            style = MyTheme.OverlineCaptionMedium,
                            textAlign = TextAlign.Start
                        )
                    }
                }
            }
        }

        // Trailing content area
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Trailing text content
            if (trailingText != null || trailingHelpText != null) {
                Column(
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.End
                ) {
                    trailingText?.let {
                        Text(
                            text = it,
                            color = textPrimary,
                            style = MyTheme.CaptionMedium,
                            textAlign = TextAlign.End
                        )
                    }
                    trailingHelpText?.let {
                        Text(
                            text = it,
                            color = MyTheme.Colors.darkGray,
                            style = MyTheme.OverlineCaptionMedium,
                            textAlign = TextAlign.End
                        )
                    }
                }
            }

            // Checkbox
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(
                        width = 1.5.dp,
                        color = if (checked) MyTheme.Colors.dashBlue else MyTheme.Colors.darkerGray50,
                        shape = RoundedCornerShape(6.dp)
                    )
                    .background(
                        if (checked) MyTheme.Colors.dashBlue else Color.Transparent
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (checked) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_checkmark_blue),
                        contentDescription = "Checked",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
@Preview
fun CheckboxSample() {
    var isChecked by remember { mutableStateOf(false) }
    var isChecked1 by remember { mutableStateOf(false) }
    var isChecked2 by remember { mutableStateOf(false) }
    var isChecked3 by remember { mutableStateOf(false) }
    var isChecked4 by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        DashCheckbox(
            checked = isChecked4,
            onCheckedChange = { isChecked4 = it },
            leadingIcon = R.drawable.ic_preview,
            title = "text",
            subtitle = "help text",
            trailingText = "text",
            trailingHelpText = "help text"
        )
        // Example 1: Simple checkbox with leading text
        DashCheckbox(
            checked = true,
            onCheckedChange = { isChecked = it },
            title = "Accept terms and conditions",
            subtitle = "Required for account creation (1 line)"
        )

        // Example 1: Simple checkbox with leading text
        DashCheckbox(
            checked = isChecked,
            onCheckedChange = { isChecked1 = it },
            title = "Accept terms and conditions and other legal rules and policies",
            subtitle = "Required for account creation (1 line)"
        )


        // Example 1: Simple checkbox with leading text
        DashCheckbox(
            checked = isChecked,
            onCheckedChange = { isChecked2 = it },
            title = "Accept terms and conditions",
            subtitle = "Required for account creation (1 line)",
            leadingIcon = R.drawable.ic_dash_pay,
            enabled = true
        )

        // Example 1: Simple checkbox with leading text
        DashCheckbox(
            checked = isChecked2,
            onCheckedChange = { isChecked2 = it },
            title = "Accept terms and conditions (Disabled)",
            subtitle = "Required for account creation (1 line)",
            leadingIcon = R.drawable.ic_dash_pay,
            enabled = false
        )

        // Example 1: Simple checkbox with long leading text
        DashCheckbox(
            checked = isChecked3,
            onCheckedChange = { isChecked3 = it },
            title = "Accept terms and conditions",
            subtitle = "Required for account creation and large downloads of information (two lines)."
        )

        // Example 2: Checkbox with both leading and trailing content

    }
}