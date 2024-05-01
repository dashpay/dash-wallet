package org.dash.wallet.common.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.dash.wallet.common.R

@Composable
fun Toast1(
    text: String,
    actionText: String,
    modifier: Modifier = Modifier,
    imageResource: Int? = null, // Add an optional parameter for the image resource
    onActionClick: () -> Unit
) {
    Snackbar(
        containerColor = Color(0xff191c1f).copy(alpha = 0.9f),
        contentColor = Color.White,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (imageResource != null) {
                Image(
                    painter = painterResource(id = imageResource),
                    contentDescription = null,
                    modifier =
                    Modifier
                        .size(24.dp)
                        .padding(end = 8.dp),
                    alignment = Alignment.BottomCenter
                )
            }
            Column(
                modifier =
                Modifier
                    .fillMaxWidth(0.8f)
                    .padding(top = 2.dp, bottom = 2.dp)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = text,
                    fontFamily = FontFamily.Default,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = Color.White,
                    maxLines = 2
                )
            }

            Column(
                modifier =
                Modifier
                    .fillMaxSize()
                    .padding(top = 4.dp, bottom = 4.dp)
                    .background(color = Color.White.copy(alpha = 0f), shape = RoundedCornerShape(6.dp)),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.End
            ) {
                TextButton(
                    onClick = onActionClick, // Pass the lambda here
                    modifier =
                        Modifier
                            .padding(top = 4.dp, bottom = 4.dp)
                            .background(
                                color = Color.White.copy(alpha = 0f),
                                shape = RoundedCornerShape(6.dp)
                            ),
                    contentPadding = PaddingValues(horizontal = 8.dp) // Add some horizontal padding
                ) {
                    Text(
                        text = actionText,
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = Color.White
                    )
                }
            }
        }
    }
}

//@Composable
//fun Toast(
//    text: String,
//    actionText: String,
//    modifier: Modifier = Modifier,
//    imageResource: Int? = null,
//    onActionClick: () -> Unit
//) {
//    Snackbar(
//        containerColor = Color(0xff191c1f).copy(alpha = 0.9f),
//        contentColor = Color.White,
//        shape = RoundedCornerShape(10.dp),
//        modifier =
//            modifier.fillMaxWidth()
//                .padding(horizontal = 5.dp, vertical = 5.dp)
//                .wrapContentHeight(Alignment.CenterVertically)
//        // .background(color = Color(0xff191c1f).copy(alpha = 0.9f), shape = RoundedCornerShape(size = 10.dp))
//        // .padding(start = 0.dp, top = 0.dp, end = 0.dp, bottom = 0.dp)
//    ) {
//        Row(
//            modifier =
//                Modifier
//                    .padding(horizontal = 0.dp, vertical = 0.dp)
//                    .fillMaxWidth()
//                    .wrapContentSize(Alignment.BottomCenter)
//                    // .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 8.dp)
//                    .background(Color(0xffFF00FF)),
//            // horizontalArrangement = Arrangement.SpaceBetween,
//            verticalAlignment = Alignment.CenterVertically
//        ) {
//            Row(
//                modifier =
//                    Modifier
//                        .weight(1f)
//                        .padding(end = 4.dp)
//                        .background(Color(0xFF0000FF)),
//                verticalAlignment = Alignment.CenterVertically
//            ) {
//                if (imageResource != null) {
//                    Image(
//                        painter = painterResource(id = imageResource),
//                        contentDescription = null,
//                        modifier =
//                            Modifier
//                                .size(24.dp)
//                                .padding(end = 0.dp)
//                    )
//                    Spacer(modifier = Modifier.width(8.dp))
//                }
//                Text(
//                    text = text,
//                    // Body 2/Regular
//                    style =
//                        TextStyle(
//                            fontSize = 14.sp,
//                            lineHeight = 20.sp,
//                            fontFamily = FontFamily(Font(R.font.inter)),
//                            fontWeight = FontWeight(400),
//                            color = Color.White
//                        ),
//                    maxLines = 2
//                    // modifier = Modifier.wrapContentSize(Alignment.BottomStart)
//                )
//            }
//            TextButton(
//                onClick = onActionClick,
//                modifier =
//                    Modifier
//                        .wrapContentSize(Alignment.BottomCenter)
//                        .background(Color.Red), // Temporarily see the space it occupies
//                // .padding(start = 0.dp)
//                // .padding(horizontal = 0.dp, vertical = 0.dp),
//                contentPadding = PaddingValues(0.dp)
//            ) {
//                Text(
//                    text = actionText,
//                    // Overline/Semibold
//                    style =
//                        TextStyle(
//                            fontSize = 12.sp,
//                            lineHeight = 16.sp,
//                            fontFamily = FontFamily(Font(R.font.inter)),
//                            fontWeight = FontWeight(600),
//                            color = Color.White,
//                            textAlign = TextAlign.Center
//                        )
//                )
//            }
//        }
//    }
//}

//@Composable
//fun Toast4(
//    text: String,
//    actionText: String,
//    modifier: Modifier = Modifier,
//    imageResource: Int? = null,
//    onActionClick: () -> Unit
//) {
//    Snackbar(
//        containerColor = Color(0xff191c1f).copy(alpha = 0.9f),
//        contentColor = Color.White,
//        shape = RoundedCornerShape(10.dp),
//        modifier =
//            modifier.fillMaxWidth()
//                .padding(horizontal = 5.dp, vertical = 5.dp)
//                .wrapContentHeight(Alignment.CenterVertically)
//        // .background(color = Color(0xff191c1f).copy(alpha = 0.9f), shape = RoundedCornerShape(size = 10.dp))
//        // .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 8.dp)
//    ) {
//        Row(
//            Modifier.padding()
//        ) {
//            if (imageResource != null) {
//                Image(
//                    painter = painterResource(id = imageResource),
//                    contentDescription = null,
//                    modifier =
//                    Modifier
//                        .size(24.dp)
//                        .padding(end = 0.dp)
//                )
//                Spacer(modifier = Modifier.width(8.dp))
//            }
//            Text(
//                text = text,
//                // Body 2/Regular
//                style = MyTheme.Body2Regular,
//                maxLines = 2
//                // modifier = Modifier.wrapContentSize(Alignment.BottomStart)
//            )
//        }
//        TextButton(
//            onClick = onActionClick,
//            modifier =
//            Modifier
//                .background(Color.Red) // Temporarily see the space it occupies
//                .padding(start = 0.dp)
//                .padding(horizontal = 0.dp, vertical = 0.dp),
//            contentPadding = PaddingValues(0.dp)
//        ) {
//            Text(
//                text = actionText,
//                // Overline/Semibold
//                style = MyTheme.OverlineSemibold
//            )
//        }
//    }
//}

enum class ToastDuration {
    SHORT,
    LONG,
    INDEFINITE
}

enum class ToastImageResource(@DrawableRes val resourceId: Int) {
    Information(R.drawable.ic_toast_info),
    Warning(R.drawable.ic_toast_info),
    Copy(R.drawable.ic_toast_copy),
    Error(R.drawable.ic_toast_error),
    NoInternet(R.drawable.ic_toast_no_wifi)
}

@Composable
fun Toast3(
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
                    // .padding(horizontal = 0.dp, vertical = 0.dp)
                    .fillMaxWidth()
                    .wrapContentSize(Alignment.BottomCenter)
                    .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 8.dp)
            //        .background(Color(0xffFF00FF))
            ,
            // horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier =
                    Modifier
                        .weight(1f),
                        //.background(Color(0xFF0000FF)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (imageResource != null) {
                    Image(
                        painter = painterResource(id = imageResource),
                        contentDescription = null,
                        modifier =
                            Modifier
                                .size(15.dp)
                                //.padding(end = 0.dp)
                                //.align(Alignment.CenterVertically) // should this be centered
                    )
                }
                Text(
                    text = text,
                    // Body 2/Regular
                    style = MyTheme.Body2Regular,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp)
                )
            }
            TextButton(
                onClick = onActionClick,
                modifier =
                    Modifier
                        //.background(Color.Red) // Temporarily see the space it occupies
                        .padding(start = 0.dp)
                        //.height(16.dp)
                        .wrapContentSize(Alignment.BottomCenter)
                        .padding(horizontal = 0.dp, vertical = 0.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(
                    text = actionText,
                    // Overline/Semibold
                    style = MyTheme.OverlineSemibold,
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

@Preview // (widthDp = 350, heightDp = 50)
@Composable
private fun ToastPreview() {
    Box(Modifier.width(400.dp).height(100.dp).background(Color.White)) {
        Toast3(
            //text = "The exchange rates .",
            text = "The exchange rates are out of date, please do something about it right away",
            actionText = "OK",
            Modifier,
            R.drawable.ic_image_placeholder
        ) {
        }
    }
}
