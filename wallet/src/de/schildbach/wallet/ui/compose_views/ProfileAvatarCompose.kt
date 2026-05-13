/*
 * Copyright 2026 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.schildbach.wallet.ui.compose_views

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import coil.transform.CircleCropTransformation
import coil.transform.Transformation
import org.dash.wallet.common.R
import org.dash.wallet.common.ui.avatar.ProfilePictureHelper
import org.dash.wallet.common.ui.avatar.ProfilePictureZoomTransformation

/**
 * Compose port of [org.dash.wallet.common.ui.avatar.ProfilePictureDisplay]. Loads via Coil with
 * the same zoom-rect crop + circle crop, and falls back to a colored-circle-with-initial
 * placeholder matching [org.dash.wallet.common.ui.avatar.UserAvatarPlaceholderDrawable].
 *
 * Caller controls the size via [modifier] (e.g. `Modifier.size(128.dp)`).
 */
@Composable
fun ProfileAvatar(
    avatarUrl: String?,
    username: String,
    modifier: Modifier = Modifier
) {
    val url = avatarUrl?.takeIf { it.isNotEmpty() }
    if (url == null) {
        AvatarPlaceholder(username = username, modifier = modifier)
        return
    }

    val context = LocalContext.current
    val parsed = remember(url) { Uri.parse(url) }
    val zoomedRect = remember(url) { ProfilePictureHelper.extractZoomedRect(parsed) }
    val baseUrl = remember(url) { ProfilePictureHelper.removePicZoomParameter(parsed) }

    val transformations: List<Transformation> = remember(zoomedRect) {
        buildList {
            zoomedRect?.let { add(ProfilePictureZoomTransformation(it)) }
            add(CircleCropTransformation())
        }
    }

    SubcomposeAsyncImage(
        model = ImageRequest.Builder(context)
            .data(baseUrl.toString())
            .transformations(transformations)
            .crossfade(true)
            .build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(CircleShape),
        loading = { AvatarPlaceholder(username = username, modifier = Modifier.fillMaxSize()) },
        error = { AvatarPlaceholder(username = username, modifier = Modifier.fillMaxSize()) }
    )
}

@Composable
private fun AvatarPlaceholder(
    username: String,
    modifier: Modifier
) {
    val firstChar = username.firstOrNull()?.uppercaseChar() ?: '?'
    val bgColor = remember(firstChar) { computeAvatarBackground(firstChar) }
    val interRegular = remember {
        FontFamily(Font(R.font.inter_regular, FontWeight.Normal))
    }

    BoxWithConstraints(
        modifier = modifier
            .clip(CircleShape)
            .background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        val sizeDp = if (maxWidth < maxHeight) maxWidth else maxHeight
        val sizePx = with(LocalDensity.current) { sizeDp.toPx() }
        // Mirror UserAvatarPlaceholderDrawable's 30/64 ratio between text and avatar width.
        val fontSizeSp = with(LocalDensity.current) { (sizePx * FONT_SIZE_RATIO).toSp() }
        Text(
            text = firstChar.toString(),
            color = Color.White,
            style = TextStyle(
                fontFamily = interRegular,
                fontWeight = FontWeight.Normal,
                fontSize = fontSizeSp
            )
        )
    }
}

private const val FONT_SIZE_RATIO: Float = 30f / 64f

private fun computeAvatarBackground(firstChar: Char): Color {
    val ascii = firstChar.code.toFloat()
    val charIndex = if (ascii <= 57f) {
        // 48 == '0'; 36 == total supported chars (0-9 + A-Z)
        (ascii - 48f) / 36f
    } else {
        (ascii - 65f + 10f) / 36f
    }
    val hue = charIndex * 360f
    return Color.hsv(hue.coerceIn(0f, 360f), 0.3f, 0.6f)
}