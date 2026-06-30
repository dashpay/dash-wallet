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

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
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

    @Deprecated(message = "obsolete font")
    val Micro = TextStyle(
        fontSize = 10.sp,
        lineHeight = 16.sp,
        fontFamily = interRegular,
        fontWeight = FontWeight(500),
        textAlign = TextAlign.Center,
    )

    @Deprecated(message = "obsolete font")
    val Caption = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontFamily = interRegular,
        fontWeight = FontWeight(400)
    )

    @Deprecated(message = "obsolete font")
    val CaptionMedium = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontFamily = interMedium,
        fontWeight = FontWeight(500)
    )

    @Deprecated(message = "obsolete font", replaceWith = ReplaceWith("Typography.LabelMediumMedium"))
    val Overline = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontFamily = interMedium,
        fontWeight = FontWeight(500),
    )

    @Deprecated(message = "obsolete font", replaceWith = ReplaceWith("Typography.LabelMediumSemibold"))
    val OverlineSemibold = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontFamily = interSemibold,
        fontWeight = FontWeight(600),
    )

    @Deprecated(message = "obsolete font", replaceWith = ReplaceWith("Typography.LabelMediumMedium"))
    val OverlineMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontFamily = interMedium,
        fontWeight = FontWeight(500)
    )

    @Deprecated(message = "obsolete font", replaceWith = ReplaceWith("Typography.LabelMediumMedium"))
    val OverlineCaptionRegular = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontFamily = interMedium,
        fontWeight = FontWeight(500)
    )

    @Deprecated(message = "obsolete font", replaceWith = ReplaceWith("Typography.LabelMedium"))
    val OverlineCaptionMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontFamily = interRegular,
        fontWeight = FontWeight(400)
    )
    @Deprecated(message = "obsolete font", replaceWith = ReplaceWith("Typography.BodyMedium"))
    val Body2Regular = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontFamily = interRegular,
        fontWeight = FontWeight(400)
    )

    @Deprecated(message = "obsolete font", replaceWith = ReplaceWith("Typography.BodyMediumMedium"))
    val Body2Medium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontFamily = interMedium,
        fontWeight = FontWeight(500)
    )

    @Deprecated(message = "obsolete font", replaceWith = ReplaceWith("Typography.TitleSmallSemibold"))
    val Subtitle2Semibold = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontFamily = interSemibold,
        fontWeight = FontWeight(600)
    )

    @Deprecated(message = "obsolete font")
    val SubtitleSemibold = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontFamily = interSemibold,
        fontWeight = FontWeight(600)
    )

    @Deprecated(message = "obsolete font")
    val H5Bold = TextStyle(
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontFamily = interBold,
        fontWeight = FontWeight(700)
    )

    @Deprecated(message = "obsolete font")
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
            fontWeight = FontWeight(700)
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
            fontWeight = FontWeight(750)
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

    data class ColorScheme(
        // Backgrounds
        val backgroundPrimary: Color,
        val backgroundSecondary: Color,
        val backgroundTertiary: Color,
        val overlayPrimary: Color,
        // Text / content
        val textPrimary: Color,
        val textSecondary: Color,
        val textTertiary: Color,
        val contentDisabled: Color,
        val contentWarning: Color,
        // Brand
        val dashBlue: Color,
        val dashBlue5: Color,
        // Palette
        val orange: Color,
        val yellow: Color,
        val green: Color,
        val systemRed: Color,
        val systemTeal: Color,
        val purple: Color,
        val red: Color,
        val red5: Color,
        val gray: Color,
        val gray300: Color,
        val gray400: Color,
        val extraLightGray: Color,
        val lightGray: Color,
        val extraDarkGray: Color,
        val ultraDarkGray: Color,
        val ultraLightGray: Color,
        val darkGray: Color,
        val darkerGray50: Color,
        // Gray scale (design system)
        val gray40: Color,
        val gray100: Color,
        val gray200: Color,
        val gray600: Color,
        // Utility
        val blue50: Color,
        // Dividers / strokes
        val divider: Color,
        val inputFocusedStroke: Color,
        val inputErrorStroke: Color,
        // Inputs / buttons
        val inputBackground: Color,
        val inputErrorBackground: Color,
        val disabledButtonBg: Color,
        val buttonRipple: Color,
        val warningYellow: Color,
        // Transaction row backgrounds
        val txSentBackground: Color,
        val txReceivedBackground: Color,
        val txOrangeBackground: Color,
        // Legacy alpha tokens (kept for existing callers)
        val primary4: Color,
        val primary5: Color,
        val primary8: Color,
        val primary40: Color,
    ) {
        /**
         * Base color palette exported from Figma (the "General" collection).
         *
         * These are the primitive, theme-independent colors that every semantic /
         * component token aliases to. The same primitive may serve different roles
         * depending on the theme — e.g. [Black] is the primary text color in light
         * mode while [WhiteAlpha90] is the primary text color in dark mode.
         *
         * Light/dark [ColorScheme] instances should reference these constants rather
         * than repeating raw hex literals.
         */
        companion object {
            // Blue
            val Blue = Color(0xFF008DE4)
            val BlueAlpha5 = Color(0x0D008DE4)
            val BlueAlpha10 = Color(0x1A008DE4)

            // Gray / Black
            val Black = Color(0xFF0A0B0D)
            val Black800 = Color(0xFF1E1F24)
            val Black900 = Color(0xFF141519)
            val BlackAlpha5 = Color(0x0D0A0B0D)
            val BlackAlpha8 = Color(0x140A0B0D)
            val BlackAlpha10 = Color(0x1A0A0B0D)
            val BlackAlpha15 = Color(0x260A0B0D)
            val BlackAlpha20 = Color(0x330A0B0D)
            val BlackAlpha30 = Color(0x4D0A0B0D)
            val BlackAlpha40 = Color(0x660A0B0D)
            val BlackAlpha50 = Color(0x800A0B0D)
            val BlackAlpha90 = Color(0xE60A0B0D)

            // Gray
            val Gray50 = Color(0xFFF5F6F7)
            val Gray100 = Color(0xFFEBEDEE)
            val Gray300 = Color(0xFFB0B6BC)
            val Gray300Alpha10 = Color(0x1AB0B6BC)
            val Gray300Alpha20 = Color(0x33B0B6BC)
            val Gray300Alpha30 = Color(0x4DB0B6BC)
            val Gray300Alpha50 = Color(0x80B0B6BC)
            val Gray400 = Color(0xFF75808A)
            val Gray400Alpha10 = Color(0x1A75808A)
            val Gray400Alpha25 = Color(0x4075808A)
            val Gray500 = Color(0xFF525C66)

            // Green
            val Green = Color(0xFF3EB489)

            // Orange
            val Orange = Color(0xFFFA9269)

            // Red
            val Red = Color(0xFFEA3943)

            // White
            val White = Color(0xFFFFFFFF)
            val WhiteAlpha5 = Color(0x0DFFFFFF)
            val WhiteAlpha10 = Color(0x1AFFFFFF)
            val WhiteAlpha15 = Color(0x26FFFFFF)
            val WhiteAlpha20 = Color(0x33FFFFFF)
            val WhiteAlpha30 = Color(0x4DFFFFFF)
            val WhiteAlpha40 = Color(0x66FFFFFF)
            val WhiteAlpha50 = Color(0x80FFFFFF)
            val WhiteAlpha60 = Color(0x99FFFFFF)
            val WhiteAlpha80 = Color(0xCCFFFFFF)
            val WhiteAlpha90 = Color(0xE6FFFFFF)
        }
    }

    val Colors = ColorScheme(
        // Backgrounds
        backgroundPrimary = ColorScheme.Gray50,
        backgroundSecondary = ColorScheme.White,
        backgroundTertiary = ColorScheme.Gray100,
        overlayPrimary = ColorScheme.BlackAlpha50,
        // Text / content
        textPrimary = ColorScheme.Black,
        textSecondary = ColorScheme.Gray500,
        textTertiary = ColorScheme.Gray400,
        contentDisabled = Color(0xFF92929C), // no Figma primitive
        contentWarning = Color(0xFFE85C4A), // no Figma primitive (distinct from Red)
        // Brand
        dashBlue = ColorScheme.Blue,
        dashBlue5 = ColorScheme.BlueAlpha5,
        // Palette
        orange = ColorScheme.Orange,
        yellow = Color(0xFFFFC043), // no Figma primitive
        green = ColorScheme.Green,
        systemRed = Color(0xFFE85C4A), // no Figma primitive
        systemTeal = Color(0xFF78C4F5), // no Figma primitive
        purple = Color(0xFF6273BD), // no Figma primitive
        red = ColorScheme.Red,
        red5 = Color(0x0DEA3943), // Red @5% — no Figma alpha primitive
        gray = ColorScheme.Gray300,
        gray300 = ColorScheme.Gray300,
        gray400 = ColorScheme.Gray400,
        extraLightGray = ColorScheme.Gray100,
        lightGray = Color(0xFFCED2D5), // no Figma primitive
        extraDarkGray = ColorScheme.Gray500,
        ultraDarkGray = Color(0xFF2D3033), // no Figma primitive
        ultraLightGray = ColorScheme.Gray50,
        darkGray = ColorScheme.Gray400,
        darkerGray50 = ColorScheme.Gray300Alpha50,
        // Gray scale (design system) — distinct from Figma Gray* primitives
        gray40 = Color(0xFFF2F3F5),
        gray100 = Color(0xFFE1E3E6),
        gray200 = Color(0xFFC4C8CC),
        gray600 = Color(0xFF5D5F61),
        // Utility
        blue50 = Color(0xFFF0F8FE),
        // Dividers / strokes
        divider = Color(0x1A191C1F),
        inputFocusedStroke = Color(0x33008DE4),
        inputErrorStroke = Color(0x33E85C4A),
        // Inputs / buttons
        inputBackground = ColorScheme.Gray100,
        inputErrorBackground = Color(0x1AE85C4A),
        disabledButtonBg = Color(0xFFEEEEEE),
        buttonRipple = Color(0x1F000000),
        warningYellow = Color(0xFFFFF9ED),
        // Transaction row backgrounds
        txSentBackground = Color(0xFFE7F4FB),
        txReceivedBackground = Color(0xFFEDF8F2),
        txOrangeBackground = Color(0xFFFDF5F1),
        // Legacy alpha tokens
        primary4 = ColorScheme.BlackAlpha8,
        primary5 = ColorScheme.BlackAlpha5,
        primary8 = ColorScheme.BlackAlpha8,
        primary40 = ColorScheme.BlackAlpha40,
    )

    val DarkColors = ColorScheme(
        // Backgrounds
        backgroundPrimary = ColorScheme.Black,
        backgroundSecondary = ColorScheme.Black800,
        // Figma dark export reports Tertiary as #EBEDEE (a light gray), which looks
        // like an un-overridden variable artifact; keep Transparent as before.
        backgroundTertiary = Color.Transparent,
        overlayPrimary = ColorScheme.BlackAlpha50,
        // Text / content (Figma dark uses white-with-opacity rather than solid grays)
        textPrimary = ColorScheme.WhiteAlpha90,
        textSecondary = ColorScheme.WhiteAlpha80,
        textTertiary = ColorScheme.WhiteAlpha60,
        contentDisabled = Color(0xFF92929C), // no Figma primitive
        contentWarning = Color(0xFFE96453), // no Figma primitive (distinct from Red)
        // Brand
        dashBlue = ColorScheme.Blue,
        dashBlue5 = ColorScheme.BlueAlpha5,
        // Palette
        orange = ColorScheme.Orange,
        yellow = Color(0xFFFFC043), // no Figma primitive
        green = ColorScheme.Green,
        systemRed = Color(0xFFE96453), // no Figma primitive
        systemTeal = Color(0xFF84C9F6), // no Figma primitive
        purple = Color(0xFF6A7CCC), // no Figma primitive
        red = ColorScheme.Red,
        red5 = Color(0x0DE96453), // no Figma alpha primitive
        gray = Color(0xFF45494D), // dark-only gray, no Figma primitive
        gray300 = Color(0xFF45494D),
        gray400 = Color(0xFF757A80),
        extraLightGray = Color(0xFFA4ABB2),
        lightGray = Color(0xFF8D9399),
        extraDarkGray = Color(0xFF45494D),
        ultraDarkGray = Color(0xFF2D3033),
        ultraLightGray = ColorScheme.Gray50,
        darkGray = Color(0xFF757A80),
        darkerGray50 = Color(0x8045494D),
        // Gray scale (no night override — same as light)
        gray40 = Color(0xFFF2F3F5),
        gray100 = Color(0xFFE1E3E6),
        gray200 = Color(0xFFC4C8CC),
        gray600 = Color(0xFF5D5F61),
        // Utility
        blue50 = Color(0xFFF0F8FE),
        // Dividers / strokes
        divider = Color(0xFF45494D),
        inputFocusedStroke = Color(0x33008DE4),
        inputErrorStroke = Color(0x33E85C4A),
        // Inputs / buttons
        inputBackground = Color(0xFF45494D),
        inputErrorBackground = Color(0x1AE85C4A),
        disabledButtonBg = Color(0xFF3C3C3C),
        buttonRipple = Color(0x30FFFFFF),
        warningYellow = Color(0xFFFFF9ED),
        // Transaction row backgrounds
        txSentBackground = Color(0xFF20262E),
        txReceivedBackground = Color(0xFF232826),
        txOrangeBackground = Color(0xFF302D2D),
        // Legacy alpha tokens
        primary4 = Color(0x14FFFFFF), // 8% white — no Figma alpha primitive
        primary5 = ColorScheme.WhiteAlpha5,
        primary8 = Color(0x14FFFFFF), // 8% white — no Figma alpha primitive
        primary40 = ColorScheme.WhiteAlpha40,
    )
}

val LocalDashColors = staticCompositionLocalOf { MyTheme.Colors }

@Composable
fun DashWalletTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) MyTheme.DarkColors else MyTheme.Colors
    CompositionLocalProvider(LocalDashColors provides colors, content = content)
}

@Composable
fun DarkPreviewTheme(composable: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDashColors provides MyTheme.DarkColors, composable)
}

@Composable
fun LightPreviewTheme(composable: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDashColors provides MyTheme.Colors, composable)
}

