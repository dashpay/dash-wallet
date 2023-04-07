/*
 * Copyright 2021 Dash Core Group.
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

package org.dash.wallet.common.ui

import android.content.res.Resources
import android.graphics.drawable.Drawable
import androidx.annotation.StyleRes
import androidx.core.content.res.ResourcesCompat
import org.dash.wallet.common.R

fun Resources.getRoundedBackground(@StyleRes style: Int): Drawable? {
    return ResourcesCompat.getDrawable(
        this,
        R.drawable.rounded_background,
        this.newTheme().apply { applyStyle(style, true) }
    )
}

fun Resources.getRoundedRippleBackground(@StyleRes style: Int): Drawable? {
    return ResourcesCompat.getDrawable(
        this,
        R.drawable.rounded_ripple_background,
        this.newTheme().apply { applyStyle(style, true) }
    )
}
