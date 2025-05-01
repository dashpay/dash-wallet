package org.dash.wallet.common.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.dash.wallet.common.R

@Composable
fun DashButton(
    modifier: Modifier = Modifier,
    text: String? = null,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    style: Style = Style.Filled,
    size: Size = Size.Large,
    stretch: Boolean = true,
    isEnabled: Boolean = true,
    isLoading: Boolean = false,
    onClick: () -> Unit
) {
    val backgroundColor = when {
        !isEnabled -> Color.Black.copy(alpha = 0.2f)
        style == Style.Filled -> DashBlue
        style == Style.FilledBlue -> DashBlue
        style == Style.FilledRed -> MyTheme.Colors.red
        style == Style.TintedBlue -> Color(0x0D008DE4)
        style == Style.TintedGray -> Color(0x1AB0B6BC)
        style == Style.FilledWhiteBlue -> Color(0xFFFFFFFF)
        style == Style.TintedWhite -> Color(0x1AFFFFFF)
        else -> Color.Transparent
    }

    val contentColor = when {
        !isEnabled -> Color.Black.copy(alpha = 0.6f)
        style == Style.Filled -> Color.White
        style == Style.FilledBlue -> Color.White
        style == Style.FilledRed -> Color.White
        style == Style.TintedBlue -> DashBlue
        style == Style.PlainBlue -> DashBlue
        style == Style.PlainBlack -> Color(0xFF191C1F)
        style == Style.PlainRed -> Color(0xFFEA3943)
        style == Style.TintedGray -> Color(0xFF191C1F)
        style == Style.StrokeGray -> Color(0xFF191C1F)
        style == Style.FilledWhiteBlue -> DashBlue
        style == Style.TintedWhite -> Color.White

        else -> PrimaryText
    }

    val borderColor = when {
        !isEnabled -> Color.Transparent
        style == Style.Outlined -> TertiaryText.copy(alpha = 0.25f)
        style == Style.StrokeGray -> Color(0x4DB3BDC7)
        else -> Color.Transparent
    }

    val buttonModifier = modifier
        .then(if (stretch) Modifier.fillMaxWidth() else Modifier)
        .height(size.minHeight)
        .background(backgroundColor, shape = RoundedCornerShape(size.cornerRadius))
        .border(BorderStroke(2.dp, borderColor), shape = RoundedCornerShape(size.cornerRadius))
        .clip(RoundedCornerShape(size.cornerRadius))
        .alpha(if (isEnabled) 1f else 0.5f)

    Button(
        onClick = onClick,
        enabled = isEnabled && !isLoading,
        modifier = buttonModifier,
        shape = RoundedCornerShape(size.cornerRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = contentColor,
            disabledContainerColor = Color.Transparent
        ),
        contentPadding = PaddingValues(horizontal = size.paddingHorizontal, vertical = size.paddingVertical),
        elevation = null
    ) {
        if (leadingIcon != null) {
            Icon(
                leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(size.iconSize),
                tint = Color.Unspecified
            )
            Spacer(modifier = Modifier.width(size.paddingHorizontal))
        }

        if (isLoading) {
            CircularProgressIndicator(
                color = contentColor,
                strokeWidth = 2.dp,
                modifier = Modifier
                    .size(size.iconSize)
                    .padding(vertical = 2.dp)
            )
            Spacer(modifier = Modifier.width(size.paddingHorizontal))
        }
        if (text != null) {
            Text(
                text,
                fontSize = size.fontSize,
                lineHeight = size.lineSize,
                fontFamily = MyTheme.InterFont,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                overflow = TextOverflow.Ellipsis,
                // modifier = Modifier.padding(vertical = 2.dp)
            )
        }

        if (trailingIcon != null) {
            Spacer(modifier = Modifier.width(size.paddingHorizontal))
            Icon(
                trailingIcon,
                contentDescription = null,
                modifier = Modifier.size(size.iconSize),
                tint = Color.Unspecified
            )
        }
    }
}

enum class Style {
                 Plain,
    Outlined,
    Filled,
    FilledBlue,
    TintedBlue,
    TintedGray,
    PlainBlue,
    PlainBlack,
    StrokeGray,
    FilledWhiteBlue,
    TintedWhite,
    PlainRed,
    FilledRed
}

enum class Size(
    val fontSize: TextUnit,
    val lineSize: TextUnit,
    val iconSize: Dp,
    val paddingHorizontal: Dp,
    val paddingVertical: Dp,
    val cornerRadius: Dp,
    val minHeight: Dp
) {
    Large(16.sp, 22.sp, 22.dp, 20.dp, 12.dp, 12.dp, 48.dp),
    Medium(14.sp, 20.sp, 20.dp, 16.dp, 10.dp, 10.dp, 42.dp),
    Small(13.sp, 18.sp, 16.dp, 12.dp, 6.dp, 8.dp, 36.dp),
    ExtraSmall(12.sp, 16.sp,13.dp, 8.dp, 4.dp, 6.dp, 28.dp)
}

val DashBlue = Color(0xFF008DE4)
val PrimaryText = Color(0xFF000000)
val TertiaryText = Color(0xFF888888)

@Composable
@Preview
fun DashButtonPreview() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colorResource(R.color.white))
            .padding(20.dp, 10.dp, 20.dp, 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DashButton(
            text = "Continue",
            onClick = { }
        )

        DashButton(
            text = "Continue",
            onClick = { }
        )

        DashButton(
            text = "Cancel this really long operation or the system will crash",
            size = Size.Small,
            onClick = { }
        )

        DashButton(
            text = "Cancel",
            size = Size.Large,
            style = Style.FilledBlue,
            stretch = false,
            onClick = { }
        )
        DashButton(
            text = "Filled Red",
            size = Size.Small,
            style = Style.FilledRed,
            stretch = false,
            onClick = { }
        )
        DashButton(
            text = "Plain Red",
            size = Size.Small,
            style = Style.PlainRed,
            stretch = false,
            onClick = { }
        )
        DashButton(
            text = "Stroke Gray",
            size = Size.Small,
            style = Style.StrokeGray,
            stretch = false,
            onClick = { }
        )
        Column(modifier = Modifier
            .background(DashBlue)
            .padding(10.dp, 20.dp)) {
            DashButton(
                text = "TintedWhite",
                size = Size.Small,
                style = Style.TintedWhite,
                stretch = false,
                onClick = { }
            )
        }
        DashButton(
            text = "Large",
            size = Size.Large,
            stretch = false,
            leadingIcon = ImageVector.vectorResource(R.drawable.ic_error),
            trailingIcon = ImageVector.vectorResource(R.drawable.ic_dash_d_white),
            isLoading = true,
            onClick = { }
        )

        DashButton(
            text = "Medium",
            size = Size.Medium,
            stretch = false,
            leadingIcon = ImageVector.vectorResource(R.drawable.ic_error),
            trailingIcon = ImageVector.vectorResource(R.drawable.ic_dash_d_white),
            isLoading = true,
            onClick = { }
        )

        DashButton(
            text = "Small",
            size = Size.Small,
            stretch = false,
            leadingIcon = ImageVector.vectorResource(R.drawable.ic_error),
            trailingIcon = ImageVector.vectorResource(R.drawable.ic_dash_d_white),
            isLoading = true,
            onClick = { }
        )

        DashButton(
            text = "Extra Small",
            size = Size.ExtraSmall,
            stretch = false,
            leadingIcon = ImageVector.vectorResource(R.drawable.ic_error),
            trailingIcon = ImageVector.vectorResource(R.drawable.ic_dash_d_white),
            isLoading = true,
            onClick = { }
        )
    }
}