package org.dash.wallet.common.ui.components

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.dash.wallet.common.R

enum class ToastDuration {
    SHORT,
    LONG,
    INDEFINITE
}

enum class ToastImageResource(@DrawableRes val resourceId: Int) {
    Information(R.drawable.ic_toast_info),
    Warning(R.drawable.ic_toast_info_warning),
    Copy(R.drawable.ic_toast_copy),
    Error(R.drawable.ic_toast_error),
    Success(R.drawable.ic_toast_success),
    Loading(R.drawable.ic_toast_loading),
    NoInternet(R.drawable.ic_toast_no_wifi)
}

@Composable
fun Toast(
    text: String,
    actionText: String? = null,
    modifier: Modifier = Modifier,
    imageResource: Int? = null,
    showDismissButton: Boolean = false,
    onDismiss: (() -> Unit)? = null,
    onActionClick: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 5.dp, vertical = 5.dp)
            .background(
                color = MyTheme.ToastBackground,
                shape = RoundedCornerShape(size = 20.dp)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // LeadingWrap: icon + message
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                if (imageResource != null) {
                    Box(
                        modifier = Modifier.size(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = imageResource),
                            contentDescription = null,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
                Text(
                    text = text,
                    style = MyTheme.Body2Regular,
                    color = Color.White,
                    modifier = Modifier.padding(vertical = 2.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Action button — plain Box to avoid Material3's 40dp minimum height
            if (actionText != null) {
                Box(
                    modifier = Modifier
                        .width(54.dp)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .clickable { onActionClick() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = actionText,
                        style = MyTheme.OverlineSemibold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Dismiss button
            if (showDismissButton) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.1f))
                        .clickable { onDismiss?.invoke() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_x),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(7.dp)
                    )
                }
            }
        }
    }
}

@Preview(name = "Toast w/ action Light", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Toast w/ action Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ToastPreview() {
    DashWalletTheme {
        Box(Modifier.width(400.dp).background(LocalDashColors.current.backgroundPrimary).padding(vertical = 4.dp)) {
            Toast(
                text = "The exchange rates are out of date, please do something about it right away",
                actionText = "OK",
                imageResource = R.drawable.ic_image_placeholder
            ) {}
        }
    }
}

@Preview(name = "Toast w/ dismiss Light", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Toast w/ dismiss Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ToastWithDismissPreview() {
    DashWalletTheme {
        Box(Modifier.width(400.dp).background(LocalDashColors.current.backgroundPrimary).padding(vertical = 4.dp)) {
            Toast(
                text = "Some coins are currently halted",
                actionText = "Action",
                imageResource = R.drawable.ic_image_placeholder,
                showDismissButton = true,
                onDismiss = {}
            ) {}
        }
    }
}

@Preview(name = "Toast Warning Light", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Toast Warning Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ToastWarningPreview() {
    DashWalletTheme {
        Box(Modifier.width(375.dp).background(LocalDashColors.current.backgroundPrimary).padding(vertical = 4.dp)) {
            Toast(
                text = "Warning",
                actionText = "Action",
                imageResource = ToastImageResource.Warning.resourceId,
                showDismissButton = true,
                onDismiss = {}
            ) {}
        }
    }
}

@Preview(name = "Toast Info Light", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Toast Info Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ToastInfoPreview() {
    DashWalletTheme {
        Box(Modifier.width(375.dp).background(LocalDashColors.current.backgroundPrimary).padding(vertical = 4.dp)) {
            Toast(
                text = "Info",
                actionText = "Action",
                imageResource = ToastImageResource.Information.resourceId,
                showDismissButton = true,
                onDismiss = {}
            ) {}
        }
    }
}

@Preview(name = "Toast Error Light", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Toast Error Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ToastErrorPreview() {
    DashWalletTheme {
        Box(Modifier.width(375.dp).background(LocalDashColors.current.backgroundPrimary).padding(vertical = 4.dp)) {
            Toast(
                text = "Error",
                actionText = "Action",
                imageResource = ToastImageResource.Error.resourceId,
                showDismissButton = true,
                onDismiss = {}
            ) {}
        }
    }
}

@Preview(name = "Toast Success Light", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Toast Success Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ToastSuccessPreview() {
    DashWalletTheme {
        Box(Modifier.width(375.dp).background(LocalDashColors.current.backgroundPrimary).padding(vertical = 4.dp)) {
            Toast(
                text = "Success",
                actionText = "Action",
                imageResource = ToastImageResource.Success.resourceId,
                showDismissButton = true,
                onDismiss = {}
            ) {}
        }
    }
}

@Preview(name = "Toast Copied Light", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Toast Copied Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ToastCopiedPreview() {
    DashWalletTheme {
        Box(Modifier.width(375.dp).background(LocalDashColors.current.backgroundPrimary).padding(vertical = 4.dp)) {
            Toast(
                text = "Copied",
                actionText = "Action",
                imageResource = ToastImageResource.Copy.resourceId,
                showDismissButton = true,
                onDismiss = {}
            ) {}
        }
    }
}

@Preview(name = "Toast Loading Light", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Toast Loading Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ToastLoadingPreview() {
    DashWalletTheme {
        Box(Modifier.width(375.dp).background(LocalDashColors.current.backgroundPrimary).padding(vertical = 4.dp)) {
            Toast(
                text = "Loading",
                actionText = "Action",
                imageResource = ToastImageResource.Loading.resourceId,
                showDismissButton = true,
                onDismiss = {}
            ) {}
        }
    }
}

@Preview(name = "Toast No internet Light", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Toast No internet Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ToastNoInternetPreview() {
    DashWalletTheme {
        Box(Modifier.width(375.dp).background(LocalDashColors.current.backgroundPrimary).padding(vertical = 4.dp)) {
            Toast(
                text = "No internet connection",
                actionText = "Action",
                imageResource = ToastImageResource.NoInternet.resourceId,
                showDismissButton = true,
                onDismiss = {}
            ) {}
        }
    }
}
