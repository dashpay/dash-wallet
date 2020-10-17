package de.schildbach.wallet.ui.dashpay.widget

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import com.ortiz.touchview.TouchImageView


class CircleCropTouchImageView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyle: Int = 0) : TouchImageView(context, attrs, defStyle) {

    var calculatedMinZoom = false

    override fun draw(canvas: Canvas) {
        val vW = measuredWidth
        val vH = measuredHeight

        val imgScaleRatio = 1f
        val imgVerticalDisplacement = 0.5f
        val bitmap = (drawable as BitmapDrawable?)?.bitmap
        if (bitmap != null) {
            val bW = bitmap.width
            val bH = bitmap.height
            //Set initial and min zoom
            //TODO: fix min zoom for vertical images
            if (!calculatedMinZoom) {
                minZoom = if (bW > bH) {
                    (vW * imgScaleRatio) / drawable.intrinsicHeight
                } else {
                    (vH * imgScaleRatio) / drawable.intrinsicWidth
                }
                calculatedMinZoom = true
                setZoom(minZoom)
            }
            val halfWidth = width / 2f

            //Capture the loaded picture ????
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val newCanvas = Canvas(bmp)
            super.draw(newCanvas)

            val path = Path()
            path.addCircle(halfWidth, vH * imgVerticalDisplacement, halfWidth * imgScaleRatio,
                    Path.Direction.CCW)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val shader: Shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            paint.shader = shader
            paint.colorFilter = null
            canvas.drawPath(path, paint)
        }
    }
}
