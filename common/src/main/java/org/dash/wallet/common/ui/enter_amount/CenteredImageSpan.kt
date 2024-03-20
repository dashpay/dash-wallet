package org.dash.wallet.common.ui.enter_amount

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.style.ReplacementSpan

class CenteredImageSpan(private val drawable: Drawable, private val context: Context) : ReplacementSpan() {
    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        val rect = drawable.bounds

        fm?.let {
            val textHeight = fm.bottom - fm.top
            val centerY = fm.top + textHeight / 2

            val drawableHeight = rect.bottom - rect.top
            val drawableCenterY = rect.top + drawableHeight / 2

            // Calculate offset to align image center to text center
            val alignmentOffset = drawableCenterY - centerY

            it.ascent = fm.ascent + alignmentOffset
            it.top = fm.top + alignmentOffset
            it.bottom = fm.bottom + alignmentOffset
            it.descent = fm.descent + alignmentOffset
        }

        return rect.right
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        canvas.save()

        val fm = paint.fontMetricsInt
        val textHeight = fm.descent - fm.ascent
        val centerY = y + fm.descent - textHeight / 2

        val drawableHeight = drawable.bounds.height()
        val drawableCenterY = drawable.bounds.top + drawableHeight / 2

        // Align the center of the drawable with the center of the text
        val transY = centerY - drawableCenterY

        canvas.translate(x, transY.toFloat())
        drawable.draw(canvas)

        canvas.restore()
    }
}
