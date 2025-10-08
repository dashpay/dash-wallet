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

    val Caption = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontFamily = interRegular,
        fontWeight = FontWeight(400)
    )

    val OverlineMedium = TextStyle(
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

    val OverlineCaptionRegular = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontFamily = interMedium,
        fontWeight = FontWeight(500)
    )

    val Body2Regular = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontFamily = interRegular,
        fontWeight = FontWeight(400)
    )

    val OverlineCaptionMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
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
