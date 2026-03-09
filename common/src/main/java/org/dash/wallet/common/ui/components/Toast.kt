package org.dash.wallet.common.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // LeadingWrap: icon + message
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
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
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Action button
            actionText?.let {
                TextButton(
                    onClick = onActionClick,
                    modifier = Modifier
                        .wrapContentSize()
                        .width(54.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
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

@Preview(name = "Toast – Warning")
@Composable
private fun ToastWarningPreview() {
    Box(Modifier.width(375.dp).background(Color.White).padding(vertical = 4.dp)) {
        Toast(
            text = "Warning",
            actionText = "Action",
            imageResource = ToastImageResource.Warning.resourceId,
            showDismissButton = true,
            onDismiss = {}
        ) {}
    }
}

@Preview(name = "Toast – Info")
@Composable
private fun ToastInfoPreview() {
    Box(Modifier.width(375.dp).background(Color.White).padding(vertical = 4.dp)) {
        Toast(
            text = "Info",
            actionText = "Action",
            imageResource = ToastImageResource.Information.resourceId,
            showDismissButton = true,
            onDismiss = {}
        ) {}
    }
}

@Preview(name = "Toast – Error")
@Composable
private fun ToastErrorPreview() {
    Box(Modifier.width(375.dp).background(Color.White).padding(vertical = 4.dp)) {
        Toast(
            text = "Error",
            actionText = "Action",
            imageResource = ToastImageResource.Error.resourceId,
            showDismissButton = true,
            onDismiss = {}
        ) {}
    }
}

@Preview(name = "Toast – Success")
@Composable
private fun ToastSuccessPreview() {
    Box(Modifier.width(375.dp).background(Color.White).padding(vertical = 4.dp)) {
        Toast(
            text = "Success",
            actionText = "Action",
            imageResource = ToastImageResource.Success.resourceId,
            showDismissButton = true,
            onDismiss = {}
        ) {}
    }
}

@Preview(name = "Toast – Copied")
@Composable
private fun ToastCopiedPreview() {
    Box(Modifier.width(375.dp).background(Color.White).padding(vertical = 4.dp)) {
        Toast(
            text = "Copied",
            actionText = "Action",
            imageResource = ToastImageResource.Copy.resourceId,
            showDismissButton = true,
            onDismiss = {}
        ) {}
    }
}

@Preview(name = "Toast – Loading")
@Composable
private fun ToastLoadingPreview() {
    Box(Modifier.width(375.dp).background(Color.White).padding(vertical = 4.dp)) {
        Toast(
            text = "Loading",
            actionText = "Action",
            imageResource = ToastImageResource.Loading.resourceId,
            showDismissButton = true,
            onDismiss = {}
        ) {}
    }
}

@Preview(name = "Toast – No internet connection")
@Composable
private fun ToastNoInternetPreview() {
    Box(Modifier.width(375.dp).background(Color.White).padding(vertical = 4.dp)) {
        Toast(
            text = "No internet connection",
            actionText = "Action",
            imageResource = ToastImageResource.NoInternet.resourceId,
            showDismissButton = true,
            onDismiss = {}
        ) {}
    }
}
