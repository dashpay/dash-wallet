package org.dash.wallet.common.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    NoInternet(R.drawable.ic_toast_no_wifi)
}

@Composable
fun Toast(
    text: String,
    actionText: String,
    modifier: Modifier = Modifier,
    imageResource: Int? = null,
    onActionClick: () -> Unit
) {
    Box(
        modifier =
            modifier.fillMaxWidth()
                .padding(horizontal = 5.dp, vertical = 5.dp)
                .background(
                    color = MyTheme.ToastBackground,
                    shape = RoundedCornerShape(size = 10.dp)
                )
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .wrapContentSize(Alignment.BottomCenter)
                    .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier =
                    Modifier
                        .weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (imageResource != null) {
                    Image(
                        painter = painterResource(id = imageResource),
                        contentDescription = null,
                        modifier =
                            Modifier
                                .size(15.dp)
                    )
                }
                Text(
                    text = text,
                    style = MyTheme.Body2Regular,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp)
                )
            }
            TextButton(
                onClick = onActionClick,
                modifier =
                    Modifier
                        .padding(start = 0.dp)
                        .wrapContentSize()
                        .padding(horizontal = 0.dp, vertical = 0.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(
                    text = actionText,
                    style = MyTheme.OverlineSemibold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Preview
@Composable
private fun ToastPreview() {
    Box(Modifier.width(400.dp).height(100.dp).background(Color.White)) {
        Toast(
            text = "The exchange rates are out of date, please do something about it right away",
            actionText = "OK",
            Modifier,
            R.drawable.ic_image_placeholder
        ) {
        }
    }
}
