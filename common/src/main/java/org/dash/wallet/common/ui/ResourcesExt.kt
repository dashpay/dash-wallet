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

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.Button
import androidx.annotation.StyleRes
import androidx.core.content.res.ResourcesCompat
import org.dash.wallet.common.R

fun View.setRoundedBackground(@StyleRes style: Int?) {
    style?.let {
        this.background = ResourcesCompat.getDrawable(
            this.resources,
            R.drawable.rounded_background,
            this.resources.newTheme().apply { applyStyle(style, true) }
        )
    }
}

fun View.setRoundedRippleBackground(@StyleRes style: Int?) {
    style?.let {
        this.background = ResourcesCompat.getDrawable(
            this.resources,
            R.drawable.rounded_ripple_background,
            this.resources.newTheme().apply { applyStyle(style, true) }
        )
    }
}

fun View.applyStyle(@StyleRes style: Int?) {
    style?.let {
        context.theme.applyStyle(style, true)
    }
}

//fun View.setRoundedRippleBackgroundButtonStyle1(@StyleRes style: Int?) {
//    style?.let {
//        val theme = this.resources.newTheme().apply { applyStyle(style, true) }
//
//        // Extract the cornerRadius attribute
////        val cornerRadiusAttr = intArrayOf(R.attr.cornerRadius)
////        val cornerRadiusArray = theme.obtainStyledAttributes(cornerRadiusAttr)
////        val cornerRadius = cornerRadiusArray.getDimension(0, 0f) // Default to 0 if not set
////        cornerRadiusArray.recycle()
//        val cornerRadius = 7.dpToPx(context).toFloat()
//
//        // Extract the titleTextColor attribute
//        val titleTextColorAttr = intArrayOf(R.attr.titleTextColor)
//        val textColorArray = theme.obtainStyledAttributes(titleTextColorAttr)
//        val titleTextColor = textColorArray.getColor(0, Color.BLACK) // Default to black if not set
//        textColorArray.recycle()
//
//        // Apply cornerRadius and titleTextColor as needed (e.g., to a button)
//        val drawable = ResourcesCompat.getDrawable(
//            this.resources,
//            R.drawable.rounded_ripple_background,
//            theme
//        )
//
//        // Assuming rounded_ripple_background is a shape drawable that supports corner radius
//        if (drawable is RippleDrawable) {
//            // For the mask
//            val maskDrawable = drawable.findDrawableByLayerId(android.R.id.mask) as? GradientDrawable
//            maskDrawable?.cornerRadius = cornerRadius
//
//            // For the content shape (background)
//            val contentDrawable = drawable.getDrawable(0) as? GradientDrawable
//            contentDrawable?.cornerRadius = cornerRadius
//        }
//
//        // Apply drawable as background and set titleTextColor
//        this.background = drawable
//        (this as? Button)?.setTextColor(titleTextColor)
//    }
//}
//
fun View.setRoundedRippleBackgroundButtonStyle(@StyleRes style: Int?, defaultCornerRadius: Int = 7) {
    style?.let {
        val theme = this.resources.newTheme().apply { applyStyle(style, true) }
        val defaultCornerRadius = defaultCornerRadius.dpToPx(this.context)

        // Resolve the cornerRadius and rippleColor attributes from the applied style
        val attrs = intArrayOf(
            R.attr.backgroundColor,
            R.attr.titleTextColor
        )

        val typedArray = theme.obtainStyledAttributes(style, attrs)

        // Extract the attributes from the typed array
        val backgroundColor = typedArray.getColor(0, Color.TRANSPARENT)
        val titleTextColor = typedArray.getColor(1, Color.BLACK) // Default to black if not set
        typedArray.recycle()

        // Apply the ripple drawable from your XML
        val rippleDrawable = ResourcesCompat.getDrawable(
            this.resources,
            R.drawable.rounded_ripple_background, // Your ripple drawable
            theme
        ) as? RippleDrawable

        rippleDrawable?.let { rd ->

            // Modify the background color, corner radius, and stroke in the ripple's shape
            val contentDrawable = rd.getDrawable(1) as? GradientDrawable
            contentDrawable?.apply {
                setColor(backgroundColor)
                cornerRadius = defaultCornerRadius.dpToPx(context).toFloat()
            }

            // Set the ripple drawable as the background
            this.background = rd
        }
        (this as? Button)?.setTextColor(titleTextColor)
    }
}

// Utility function to convert dp to px for stroke width
fun Int.dpToPx(context: Context): Int {
    return (this * context.resources.displayMetrics.density).toInt()
}
