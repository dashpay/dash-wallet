/*
 * Copyright 2020 Dash Core Group.
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

package org.dash.wallet.common.ui.avatar

import android.content.Context
import android.graphics.Color
import androidx.core.content.res.ResourcesCompat
import com.amulyakhare.textdrawable.TextDrawable
import org.dash.wallet.common.R

class UserAvatarPlaceholderDrawable {

    companion object {
        @JvmStatic
        fun getDrawable(context: Context, usernameFirstChar: Char, fontSize: Int): TextDrawable? {
            val hsv = FloatArray(3)
            //Ascii codes for A: 65 - Z: 90, 0: 48 - 9: 57
            //Ascii codes for A: 65 - Z: 90, 0: 48 - 9: 57
            val firstChar: Float = usernameFirstChar.uppercaseChar().toFloat()
            val charIndex: Float
            charIndex = if (firstChar <= 57) { //57 == '9' in Ascii table
                (firstChar - 48f) / 36f // 48 == '0', 36 == total count of supported
            } else {
                (firstChar - 65f + 10f) / 36f // 65 == 'A', 10 == count of digits
            }
            hsv[0] = charIndex * 360f
            hsv[1] = 0.3f
            hsv[2] = 0.6f
            val bgColor = Color.HSVToColor(hsv)
            return TextDrawable.builder().beginConfig().textColor(Color.WHITE)
                    .useFont(ResourcesCompat.getFont(context, R.font.inter_regular))
                    .fontSize(fontSize)
                    .endConfig().buildRound(usernameFirstChar.toString().uppercase(), bgColor)
        }
    }
}