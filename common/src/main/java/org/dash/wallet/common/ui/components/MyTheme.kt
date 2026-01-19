/*
 * Copyright 2024 Dash Core Group.
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

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import org.dash.wallet.common.R

object MyTheme {
    val ToastBackground = Color(0xff191c1f).copy(alpha = 0.9f)
    val interRegular = FontFamily(Font(R.font.inter_regular))
    private val interMedium = FontFamily(Font(R.font.inter_medium))
    private val interSemibold = FontFamily(Font(R.font.inter_semibold))
    private val interBold = FontFamily(Font(R.font.inter_bold))

    val Micro = TextStyle(
        fontSize = 10.sp,
        lineHeight = 16.sp,
        fontFamily = interRegular,
        fontWeight = FontWeight(500),
        textAlign = TextAlign.Center,
    )

    val Caption = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontFamily = interRegular,
        fontWeight = FontWeight(400)
    )

    val CaptionMedium = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontFamily = interMedium,
        fontWeight = FontWeight(500)
    )

    val Overline = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontFamily = interMedium,
        fontWeight = FontWeight(500),
    )

    val OverlineSemibold = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontFamily = interSemibold,
        fontWeight = FontWeight(600),
    )

    val OverlineMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontFamily = interMedium,
        fontWeight = FontWeight(500)
    )

    val OverlineCaptionRegular = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontFamily = interMedium,
        fontWeight = FontWeight(500)
    )

    val OverlineCaptionMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontFamily = interRegular,
        fontWeight = FontWeight(400)
    )

    val Body2Regular = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontFamily = interRegular,
        fontWeight = FontWeight(400)
    )

    val Body2Medium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontFamily = interMedium,
        fontWeight = FontWeight(500)
    )

    val Subtitle2Semibold = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontFamily = interSemibold,
        fontWeight = FontWeight(600)
    )

    val SubtitleSemibold = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontFamily = interSemibold,
        fontWeight = FontWeight(600)
    )

    val H5Bold = TextStyle(
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontFamily = interBold,
        fontWeight = FontWeight(700)
    )

    val H6Bold = TextStyle(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontFamily = interBold,
        fontWeight = FontWeight(700)
    )

    object Typography {
        // Display styles - Largest text, typically for hero sections
        val DisplayLargeBold = TextStyle(
            fontSize = 57.sp,
            lineHeight = 64.sp,
            fontFamily = interBold,
            fontWeight = FontWeight(700)
        )

        val DisplayLargeMedium = TextStyle(
            fontSize = 57.sp,
            lineHeight = 64.sp,
            fontFamily = interMedium,
            fontWeight = FontWeight(500)
        )

        val DisplayLarge = TextStyle(
            fontSize = 57.sp,
            lineHeight = 64.sp,
            fontFamily = interRegular,
            fontWeight = FontWeight(400)
        )

        val DisplayMediumBold = TextStyle(
            fontSize = 45.sp,
            lineHeight = 52.sp,
            fontFamily = interBold,
            fontWeight = FontWeight(700)
        )

        val DisplayMediumMedium = TextStyle(
            fontSize = 45.sp,
            lineHeight = 52.sp,
            fontFamily = interMedium,
            fontWeight = FontWeight(500)
        )

        val DisplayMedium = TextStyle(
            fontSize = 45.sp,
            lineHeight = 52.sp,
            fontFamily = interRegular,
            fontWeight = FontWeight(400)
        )

        val DisplaySmallBold = TextStyle(
            fontSize = 36.sp,
            lineHeight = 44.sp,
            fontFamily = interBold,
            fontWeight = FontWeight(700)
        )

        val DisplaySmallMedium = TextStyle(
            fontSize = 36.sp,
            lineHeight = 44.sp,
            fontFamily = interMedium,
            fontWeight = FontWeight(500)
        )

        val DisplaySmall = TextStyle(
            fontSize = 36.sp,
            lineHeight = 44.sp,
            fontFamily = interRegular,
            fontWeight = FontWeight(400)
        )

        // Headline styles - Large headers
        val HeadlineLargeBold = TextStyle(
            fontSize = 32.sp,
            lineHeight = 40.sp,
            fontFamily = interBold,
            fontWeight = FontWeight(650)
        )

        val HeadlineLargeSemibold = TextStyle(
            fontSize = 32.sp,
            lineHeight = 40.sp,
            fontFamily = interSemibold,
            fontWeight = FontWeight(600)
        )

        val HeadlineLargeMedium = TextStyle(
            fontSize = 32.sp,
            lineHeight = 40.sp,
            fontFamily = interMedium,
            fontWeight = FontWeight(500)
        )

        val HeadlineLarge = TextStyle(
            fontSize = 32.sp,
            lineHeight = 40.sp,
            fontFamily = interRegular,
            fontWeight = FontWeight(400)
        )

        val HeadlineMediumBold = TextStyle(
            fontSize = 28.sp,
            lineHeight = 36.sp,
            fontFamily = interBold,
            fontWeight = FontWeight(650)
        )

        val HeadlineMediumSemibold = TextStyle(
            fontSize = 28.sp,
            lineHeight = 36.sp,
            fontFamily = interSemibold,
            fontWeight = FontWeight(600)
        )

        val HeadlineMediumMedium = TextStyle(
            fontSize = 28.sp,
            lineHeight = 36.sp,
            fontFamily = interMedium,
            fontWeight = FontWeight(500)
        )

        val HeadlineMedium = TextStyle(
            fontSize = 28.sp,
            lineHeight = 36.sp,
            fontFamily = interRegular,
            fontWeight = FontWeight(400)
        )

        val HeadlineSmallBold = TextStyle(
            fontSize = 24.sp,
            lineHeight = 32.sp,
            fontFamily = interBold,
            fontWeight = FontWeight(650)
        )

        val HeadlineSmallSemibold = TextStyle(
            fontSize = 24.sp,
            lineHeight = 32.sp,
            fontFamily = interSemibold,
            fontWeight = FontWeight(600)
        )

        val HeadlineSmallMedium = TextStyle(
            fontSize = 24.sp,
            lineHeight = 32.sp,
            fontFamily = interMedium,
            fontWeight = FontWeight(500)
        )

        val HeadlineSmall = TextStyle(
            fontSize = 24.sp,
            lineHeight = 32.sp,
            fontFamily = interRegular,
            fontWeight = FontWeight(400)
        )

        // Title styles - Smaller headers and section titles
        val TitleLargeBold = TextStyle(
            fontSize = 22.sp,
            lineHeight = 28.sp,
            fontFamily = interBold,
            fontWeight = FontWeight(700)
        )

        val TitleLargeSemibold = TextStyle(
            fontSize = 22.sp,
            lineHeight = 28.sp,
            fontFamily = interSemibold,
            fontWeight = FontWeight(600)
        )

        val TitleLargeMedium = TextStyle(
            fontSize = 22.sp,
            lineHeight = 28.sp,
            fontFamily = interMedium,
            fontWeight = FontWeight(500)
        )

        val TitleLarge = TextStyle(
            fontSize = 22.sp,
            lineHeight = 28.sp,
            fontFamily = interRegular,
            fontWeight = FontWeight(400)
        )

        val TitleMediumBold = TextStyle(
            fontSize = 16.sp,
            lineHeight = 24.sp,
            fontFamily = interBold,
            fontWeight = FontWeight(700)
        )

        val TitleMediumSemibold = TextStyle(
            fontSize = 16.sp,
            lineHeight = 24.sp,
            fontFamily = interSemibold,
            fontWeight = FontWeight(600)
        )

        val TitleMediumMedium = TextStyle(
            fontSize = 16.sp,
            lineHeight = 24.sp,
            fontFamily = interMedium,
            fontWeight = FontWeight(500)
        )

        val TitleMedium = TextStyle(
            fontSize = 16.sp,
            lineHeight = 24.sp,
            fontFamily = interRegular,
            fontWeight = FontWeight(400)
        )

        val TitleSmallBold = TextStyle(
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontFamily = interBold,
            fontWeight = FontWeight(700)
        )

        val TitleSmallSemibold = TextStyle(
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontFamily = interSemibold,
            fontWeight = FontWeight(600)
        )

        val TitleSmallMedium = TextStyle(
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontFamily = interMedium,
            fontWeight = FontWeight(500)
        )

        val TitleSmall = TextStyle(
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontFamily = interRegular,
            fontWeight = FontWeight(400)
        )

        // Body styles - Main body text
        val BodyLargeBold = TextStyle(
            fontSize = 16.sp,
            lineHeight = 24.sp,
            fontFamily = interBold,
            fontWeight = FontWeight(700)
        )

        val BodyLargeSemibold = TextStyle(
            fontSize = 16.sp,
            lineHeight = 24.sp,
            fontFamily = interSemibold,
            fontWeight = FontWeight(600)
        )

        val BodyLargeMedium = TextStyle(
            fontSize = 16.sp,
            lineHeight = 24.sp,
            fontFamily = interMedium,
            fontWeight = FontWeight(500)
        )

        val BodyLarge = TextStyle(
            fontSize = 16.sp,
            lineHeight = 24.sp,
            fontFamily = interRegular,
            fontWeight = FontWeight(400)
        )

        val BodyMediumBold = TextStyle(
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontFamily = interBold,
            fontWeight = FontWeight(700)
        )

        val BodyMediumSemibold = TextStyle(
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontFamily = interSemibold,
            fontWeight = FontWeight(600)
        )

        val BodyMediumMedium = TextStyle(
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontFamily = interMedium,
            fontWeight = FontWeight(500)
        )

        val BodyMedium = TextStyle(
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontFamily = interRegular,
            fontWeight = FontWeight(400)
        )

        val BodySmallBold = TextStyle(
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontFamily = interBold,
            fontWeight = FontWeight(700)
        )

        val BodySmallSemibold = TextStyle(
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontFamily = interSemibold,
            fontWeight = FontWeight(600)
        )

        val BodySmallMedium = TextStyle(
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontFamily = interMedium,
            fontWeight = FontWeight(500)
        )

        val BodySmall = TextStyle(
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontFamily = interRegular,
            fontWeight = FontWeight(400)
        )

        // Label styles - Labels, captions, and metadata text
        val LabelLargeBold = TextStyle(
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontFamily = interBold,
            fontWeight = FontWeight(700)
        )

        val LabelLargeSemibold = TextStyle(
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontFamily = interSemibold,
            fontWeight = FontWeight(600)
        )

        val LabelLargeMedium = TextStyle(
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontFamily = interMedium,
            fontWeight = FontWeight(500)
        )

        val LabelLarge = TextStyle(
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontFamily = interRegular,
            fontWeight = FontWeight(400)
        )

        val LabelMediumBold = TextStyle(
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontFamily = interBold,
            fontWeight = FontWeight(700)
        )

        val LabelMediumSemibold = TextStyle(
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontFamily = interSemibold,
            fontWeight = FontWeight(600)
        )

        val LabelMediumMedium = TextStyle(
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontFamily = interMedium,
            fontWeight = FontWeight(500)
        )

        val LabelMedium = TextStyle(
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontFamily = interRegular,
            fontWeight = FontWeight(400)
        )

        val LabelSmallBold = TextStyle(
            fontSize = 11.sp,
            lineHeight = 16.sp,
            fontFamily = interBold,
            fontWeight = FontWeight(700)
        )

        val LabelSmallSemibold = TextStyle(
            fontSize = 11.sp,
            lineHeight = 16.sp,
            fontFamily = interSemibold,
            fontWeight = FontWeight(600)
        )

        val LabelSmallMedium = TextStyle(
            fontSize = 11.sp,
            lineHeight = 16.sp,
            fontFamily = interMedium,
            fontWeight = FontWeight(500)
        )

        val LabelSmall = TextStyle(
            fontSize = 11.sp,
            lineHeight = 16.sp,
            fontFamily = interRegular,
            fontWeight = FontWeight(400)
        )

        // Legacy aliases for backward compatibility
        @Deprecated("Use HeadlineLargeBold instead", ReplaceWith("HeadlineLargeBold"))
        val HeadlineLBold = HeadlineLargeBold

        @Deprecated("Use HeadlineMediumBold instead", ReplaceWith("HeadlineMediumBold"))
        val HeadlineMBold = HeadlineMediumBold

        @Deprecated("Use HeadlineSmallBold instead", ReplaceWith("HeadlineSmallBold"))
        val HeadlineSBold = HeadlineSmallBold
    }

    object Colors {
        val backgroundPrimary = Color(0xFFF5F6F7)
        val textPrimary = Color(0xFF191C1F)
        val backgroundSecondary = Color(0xFFFFFFFF)
        val textSecondary = Color(0xFF6E757C)
        val textTertiary = Color(0xff75808A)
        val divider = Color(0x1A191C1F)
        val primary4 = Color(0x14191C1F)
        val primary8 = Color(0x14191C1F)
        val primary5 = Color(0x0D191C1F)
        val primary40 = Color(0x66191C1F)
        val dashBlue = Color(0xFF008DE4)
        val dashBlue5 = Color(0x0D008DE4)
        val orange = Color(0xFFFA9269)
        val yellow = Color(0xFFFFC043)
        val green = Color(0xFF3CB878)
        val gray = Color(0xFFB0B6BC)
        val gray300 = Color(0xFFB0B6BC)
        val gray400 = Color(0xFF75808A)
        val red = Color(0xFFEA3943)
        val red5 = Color(0x0DEA3943)
        val extraLightGray = Color(0xFFEBEDEE)
        val darkGray = Color(0xFF75808A)
        val darkerGray50 = Color(0x80B0B6BC)
    }
}
