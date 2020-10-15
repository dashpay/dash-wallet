package de.schildbach.wallet.ui.dashpay.widget

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.util.AttributeSet
import android.view.View
import com.ortiz.touchview.TouchImageView


class CircleCropTouchImageView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyle: Int = 0) : TouchImageView(context, attrs, defStyle) {

    var blurredBitmap: Bitmap? = null

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        val imgScaleRatio = 0.7f
        val bitmap = (drawable as BitmapDrawable?)?.bitmap
        if (bitmap != null) {
            if (blurredBitmap == null) {
                blurredBitmap = blur(this.context, bitmap)
                blurredBitmap = blur(this.context, blurredBitmap!!)
                blurredBitmap = blur(this.context, blurredBitmap!!)
            }
            // Draw a circle with the required radius.
            val halfWidth = (width / 2f)
            val halfHeight = (height / 2f)

            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val newCanvas = Canvas(bmp)
            super.draw(newCanvas)

            // Load the bitmap as a shader to the paint.
            var path = Path()
            path.addCircle(halfWidth, halfHeight * imgScaleRatio, halfWidth * imgScaleRatio,
                    Path.Direction.CCW)
            var paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val colorMatrix = ColorMatrix()
            val colorScale = 0.9f
            colorMatrix.setScale(colorScale, colorScale, colorScale, 1f)
            paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
            canvas.drawBitmap(blurredBitmap, this.matrix, paint)
            val shader: Shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            paint.shader = shader
            paint.colorFilter = null
            canvas.drawPath(path, paint)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Load the bitmap as a shader to the paint.

        /*
        val bitmap = (drawable as BitmapDrawable?)?.bitmap
        if (bitmap != null) {
            if (blurredBitmap == null) {
                blurredBitmap = blur(this.context, bitmap)
            }
            // Draw a circle with the required radius.
            val halfWidth = (width / 2f)
            val halfHeight = (height / 2f)


            // Load the bitmap as a shader to the paint.
            /*
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val colorMatrix = ColorMatrix()
            colorMatrix.setSaturation(0F)
            paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
            val shader: Shader = BitmapShader(blurredBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            paint.shader = shader

            canvas.drawCircle(halfWidth, halfHeight, halfWidth*.8f, paint)

             */

            var path = Path()
            path.addCircle(halfWidth, halfHeight, halfWidth*.8f, Path.Direction.CCW)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            canvas.drawPath(path, paint)

            //paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_ATOP)
        }

         */
    }

    private val BLUR_RADIUS = 25f

    private fun blur(ctx: Context?, inputBitmap: Bitmap): Bitmap? {
        val outputBitmap = Bitmap.createBitmap(inputBitmap)
        val rs: RenderScript = RenderScript.create(ctx)
        val theIntrinsic = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
        val tmpIn: Allocation = Allocation.createFromBitmap(rs, inputBitmap)
        val tmpOut: Allocation = Allocation.createFromBitmap(rs, outputBitmap)
        theIntrinsic.setRadius(BLUR_RADIUS)
        theIntrinsic.setInput(tmpIn)
        theIntrinsic.forEach(tmpOut)
        tmpOut.copyTo(outputBitmap)
        return outputBitmap
    }

    private fun getScreenshot(v: View): Bitmap {
        val b = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        v.draw(c)
        return b
    }
}
