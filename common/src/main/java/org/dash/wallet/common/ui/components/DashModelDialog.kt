package org.dash.wallet.common.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.dash.wallet.common.R

/**
 * A modal dialog component following the design system.
 *
 * Figma Node ID: 2878:103004 (modal.dialogue)
 *
 * @param showDialog Whether to show the dialog
 * @param onDismissRequest Called when the user dismisses the dialog
 * @param icon Optional icon to be displayed at the top of the dialog
 * @param iconBackgroundColor Background color for the icon (default: dashBlue)
 * @param heading The dialog heading text
 * @param textBlocks List of text blocks to be displayed in the dialog
 * @param limitationItems Optional list of limitation items with values and labels
 * @param moreInfoButton Optional button for additional information
 * @param buttons List of button data with labels and click actions
 * @param content Optional custom content to display after text blocks
 * @param horizontalPadding Horizontal padding for the dialog (default: 15.dp)
 */
@Composable
fun ModalDialog(
    showDialog: Boolean,
    onDismissRequest: () -> Unit,
    icon: ImageVector? = null,
    heading: String,
    textBlocks: List<String> = emptyList(),
    textAlign: TextAlign = TextAlign.Center,
    limitationItems: List<LimitationItem> = emptyList(),
    moreInfoButton: ButtonData? = null,
    buttons: List<ButtonData> = emptyList(),
    content: @Composable (() -> Unit)? = null,
    horizontalPadding: androidx.compose.ui.unit.Dp = 15.dp
) {
    if (showDialog) {
        Dialog(
            onDismissRequest = onDismissRequest,
            properties = DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            ),
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 32.dp, bottom = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Info icon if provided
                    icon?.let {
                        Box(
                            modifier = Modifier
                                .size(46.dp),
                                //.background(iconBackgroundColor, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = Color.Unspecified,
                                modifier = Modifier.size(46.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                    }

                    // Content wrapper
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Heading and text blocks
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = heading,
                                style = MyTheme.SubtitleSemibold,
                                textAlign = textAlign,
                                color = Color(0xFF191C1F)
                            )

                            // First part of text blocks (before limitation items)
                            val textBlocksPart1 = if (limitationItems.isEmpty()) textBlocks else textBlocks.take(2)
                            textBlocksPart1.forEach { textBlock ->
                                Text(
                                    text = textBlock,
                                    style = MyTheme.Body2Regular,
                                    textAlign = textAlign,
                                    color = Color(0xFF525C66)
                                )
                            }
                            Spacer(modifier = Modifier.height(20.dp))
                        }

                        // Limitation items if provided
                        if (limitationItems.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth(),
                                    //.padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                limitationItems.forEach { item ->
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Text(
                                                text = item.value,
                                                style = MyTheme.OverlineCaptionMedium,
                                                color = Color(0xFF191C1F)
                                            )
                                            if (item.showDashIcon) {
                                                Icon(
                                                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_dash_d_black),
                                                    contentDescription = null,
                                                    tint = Color(0xFF191C1F),
                                                    modifier = Modifier.size(12.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            text = item.label,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Normal,
                                            lineHeight = 16.sp,
                                            color = Color(0xFF525C66)
                                        )
                                    }
                                }
                            }

                            // Remaining text blocks after limitation items
                            if (textBlocks.size > 2) {
                                textBlocks.drop(2).forEach { textBlock ->
                                    Text(
                                        text = textBlock,
                                        style = MyTheme.Body2Regular,
                                        textAlign = textAlign,
                                        color = Color(0xFF525C66)
                                    )
                                }
                            }
                        }

                        // Custom content (if provided)
                        content?.invoke()
                    }

                    // Learn More Button
                    moreInfoButton?.let {
                        DashButton(
                            text = moreInfoButton.label,
                            style = Style.PlainBlue,
                            size = Size.Small,
                            onClick = moreInfoButton.onClick,
                        )
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    // Bottom buttons group
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        buttons.forEach { buttonData ->
                            DashButton(
                                text = buttonData.label,
                                onClick = buttonData.onClick,
                                modifier = Modifier.fillMaxWidth(),
                                size = Size.Medium,
                                style = buttonData.style,
                                isEnabled = buttonData.enabled,
                                isLoading = buttonData.progress
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Data class for limitation items displayed in the dialog
 */
data class LimitationItem(
    val value: String,
    val label: String,
    val showDashIcon: Boolean = false
)

/**
 * Data class for button configuration
 */
data class ButtonData(
    val label: String,
    val onClick: () -> Unit,
    val isPrimary: Boolean = false,
    val style: Style = Style.PlainBlue,
    val enabled: Boolean = true,
    val progress: Boolean = false
)

@Preview(showBackground = true)
@Composable
fun ModalDialogPreview() {
    ModalDialog(
        showDialog = true,
        onDismissRequest = { },
        icon = ImageVector.vectorResource(id = R.drawable.ic_info_blue),
        heading = "Heading",
        textBlocks = listOf(
            "This is the first text block with some information for the user",
            "This is the second text block with additional details",
            "And a final text block at the bottom of the dialog"
        ),
        limitationItems = listOf(
            LimitationItem("0", "text", true),
            LimitationItem("0", "text", true),
            LimitationItem("0", "text", true)
        ),
        moreInfoButton = ButtonData("Learn more", {}),
        buttons = listOf(
            ButtonData("Primary Action", {}, true),
            ButtonData("Secondary Action", {}),
            ButtonData("Tertiary Action", {})
        )
    )
}