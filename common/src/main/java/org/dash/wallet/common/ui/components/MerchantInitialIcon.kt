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

package org.dash.wallet.common.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import org.dash.wallet.common.R
import android.graphics.Color as AndroidColor

/**
 * A generated fallback icon for a merchant that has no logo. Renders the merchant's
 * initials in white on a circle whose colour is derived deterministically from the
 * name, so the same merchant always looks the same.
 *
 * Matches the colour convention of [org.dash.wallet.common.ui.avatar.UserAvatarPlaceholderDrawable]
 * (HSV with fixed 30% saturation / 60% brightness) so generated merchant icons sit
 * visually alongside contact avatars.
 *
 * @param merchantName the merchant's display name (e.g. "Home Depot").
 * @param size the diameter of the icon. The initials scale with this.
 * @param shape the icon outline; defaults to a circle. Pass a [RoundedCornerShape] for a squircle.
 */
@Composable
fun MerchantInitialIcon(
    merchantName: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    shape: Shape = CircleShape
) {
    val initials = remember(merchantName) { merchantInitials(merchantName) }
    val background = remember(merchantName) { merchantInitialColor(merchantName) }

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            style = MyTheme.Typography.BodyMediumSemibold.copy(
                fontSize = (size.value * 0.4f).sp,
                textAlign = TextAlign.Center
            ),
            color = Color.White,
            maxLines = 1
        )
    }
}

/**
 * A generated fallback icon that shows the merchant's full name, one word per line,
 * in white on the same deterministic colour as [MerchantInitialIcon]. Useful when the
 * initials alone aren't recognisable enough.
 *
 * "Home Depot" renders as "Home" / "Depot"; single-word names ("Amazon", "Brinker")
 * render on one line. The font size auto-fits so longer names ("Mortons The Steak
 * House") stay inside the circle instead of overflowing or being clipped.
 *
 * @param merchantName the merchant's display name (e.g. "Home Depot").
 * @param size the diameter of the icon. The text scales to fit within this.
 * @param shape the icon outline; defaults to a circle. A [RoundedCornerShape] gives more
 *   usable area, so the name renders larger.
 */
@Composable
fun MerchantNameIcon(
    merchantName: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    shape: Shape = CircleShape
) {
    val background = remember(merchantName) { merchantInitialColor(merchantName) }
    val lines = remember(merchantName) { merchantNameLines(merchantName) }
    val words = remember(lines) { if (lines.isEmpty()) emptyList() else lines.split("\n") }
    // A circle only exposes its inscribed square (~0.66 of the diameter); a rounded
    // square exposes almost the whole box, so the text can be larger there.
    val usableFraction = if (shape === CircleShape) 0.66f else 0.84f
    val fontSize = remember(words, size, usableFraction) { fitNameFontSize(words, size, usableFraction) }

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = lines,
            style = MyTheme.Typography.BodyMediumSemibold.copy(
                fontSize = fontSize,
                lineHeight = fontSize * 1.15f,
                textAlign = TextAlign.Center
            ),
            color = Color.White,
            maxLines = words.size.coerceAtLeast(1)
        )
    }
}

/**
 * Picks a font size so the full name (one word per line) fits inside the icon of the
 * given [size]. Constrained by both the number of lines (height) and the longest word
 * (width) against the usable area ([usableFraction] of the box — smaller for a circle,
 * which only exposes its inscribed square), then capped so short single words aren't
 * oversized. Estimate-based (no measurement pass) — good enough for a fallback icon and
 * works on Compose versions without BasicText auto-sizing.
 */
private fun fitNameFontSize(words: List<String>, size: Dp, usableFraction: Float): TextUnit {
    if (words.isEmpty()) return 1.sp
    val usable = size.value * usableFraction
    val longestWord = words.maxOf { it.length }.coerceAtLeast(1)
    val byHeight = usable / (words.size * 1.15f)   // line height ≈ 1.15 × font size
    val byWidth = usable / (longestWord * 0.58f)   // avg Inter glyph ≈ 0.58 × font size
    val maxFont = size.value * 0.4f
    return minOf(byHeight, byWidth, maxFont).coerceAtLeast(6f).sp
}

/**
 * Initials for a merchant name: the first letter of up to the first two words.
 * "Home Depot" -> "HD", "Brinker" -> "B", "Amazon" -> "A". Empty if [name] is blank.
 */
fun merchantInitials(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> ""
        words.size == 1 -> words[0].take(1).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

/**
 * The merchant's full name with each word on its own line.
 * "Home Depot" -> "Home\nDepot"; "Amazon" -> "Amazon". Empty if [name] is blank.
 */
fun merchantNameLines(name: String): String =
    name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString("\n")

/**
 * Deterministic background colour for a merchant name. Uses the same HSV scheme as the
 * contact-avatar placeholder (fixed 30% saturation, 60% brightness) but spreads the hue
 * over a hash of the whole name so different merchants get distinct colours.
 */
fun merchantInitialColor(name: String): Color {
    val key = name.trim().lowercase()
    val hue = if (key.isEmpty()) 0f else (((key.hashCode() % 360) + 360) % 360).toFloat()
    val hsv = floatArrayOf(hue, 0.3f, 0.6f)
    return Color(AndroidColor.HSVToColor(hsv))
}

/**
 * Renders the full-name icon ([MerchantNameIcon]) as a circular [Bitmap], for places where
 * Compose isn't available — e.g. a Google Maps marker. Draws the merchant's full name (one
 * word per line) in white on the deterministic [merchantInitialColor], auto-sized via real
 * text measurement so it fits inside the circle.
 *
 * @param sizePx width and height of the returned square bitmap, in pixels.
 */
fun merchantNameBitmap(context: Context, name: String, sizePx: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val radius = sizePx / 2f

    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = merchantInitialColor(name).toArgb() }
    canvas.drawCircle(radius, radius, radius, bgPaint)

    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return bitmap

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(context, R.font.inter_semibold)
    }

    // Shrink the text until every word (width) and all lines (height) fit the circle's
    // inscribed square (~0.66 of the diameter).
    val usable = sizePx * 0.66f
    var textSize = sizePx * 0.4f
    while (textSize > 4f) {
        textPaint.textSize = textSize
        val fm = textPaint.fontMetrics
        val lineHeight = fm.descent - fm.ascent
        val widest = words.maxOf { textPaint.measureText(it) }
        if (lineHeight * words.size <= usable && widest <= usable) break
        textSize -= 1f
    }

    val fm = textPaint.fontMetrics
    val lineHeight = fm.descent - fm.ascent
    var baseline = (sizePx - lineHeight * words.size) / 2f - fm.ascent
    for (word in words) {
        canvas.drawText(word, radius, baseline, textPaint)
        baseline += lineHeight
    }
    return bitmap
}

@Preview(showBackground = true)
@Composable
private fun MerchantInitialIconPreview() {
    Column(
        modifier = Modifier
            .background(MyTheme.Colors.backgroundSecondary)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        val squircle = RoundedCornerShape(28)

        // Per merchant: initials (circle), initials (squircle), name (circle), name (squircle).
        listOf("Home Depot", "Brinker", "Amazon", "Mortons The Steak House").forEach { name ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MerchantInitialIcon(merchantName = name, size = 56.dp)
                MerchantInitialIcon(merchantName = name, size = 56.dp, shape = squircle)
                MerchantNameIcon(merchantName = name, size = 56.dp)
                MerchantNameIcon(merchantName = name, size = 56.dp, shape = squircle)
                Text(
                    text = name,
                    style = MyTheme.Typography.LabelMedium,
                    color = MyTheme.Colors.textPrimary
                )
            }
        }

        // Scaling check: the long name in both shapes at the sizes used around the app.
        listOf(24.dp, 32.dp, 40.dp, 56.dp, 72.dp).let { sizes ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                sizes.forEach { d -> MerchantNameIcon(merchantName = "Mortons The Steak House", size = d) }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                sizes.forEach { d ->
                    MerchantNameIcon(merchantName = "Mortons The Steak House", size = d, shape = squircle)
                }
            }
        }
    }
}