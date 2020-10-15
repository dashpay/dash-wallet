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
            //Create blurred bitmap for the background
            if (blurredBitmap == null) {
                //TODO: Set blur times as argument of the blurring function
                blurredBitmap = blur(this.context, bitmap)
                blurredBitmap = blur(this.context, blurredBitmap!!)
                blurredBitmap = blur(this.context, blurredBitmap!!)
            }

            val halfWidth = width / 2f
            val halfHeight = height / 2f

            //Capture the loaded picture ????
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val newCanvas = Canvas(bmp)
            super.draw(newCanvas)

            val path = Path()
            path.addCircle(halfWidth, halfHeight * imgScaleRatio, halfWidth * imgScaleRatio,
                    Path.Direction.CCW)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            val colorMatrix = ColorMatrix()
            val colorScale = 0.9f
            colorMatrix.setScale(colorScale, colorScale, colorScale, 1f)
            paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
            canvas.drawBitmap(blurredBitmap!!, this.matrix, paint)

            val shader: Shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            paint.shader = shader
            paint.colorFilter = null
            canvas.drawPath(path, paint)
        }
    }

   private val blurRadius = 25f

    private fun blur(ctx: Context?, inputBitmap: Bitmap): Bitmap? {
        val outputBitmap = Bitmap.createBitmap(inputBitmap)
        val rs: RenderScript = RenderScript.create(ctx)
        val theIntrinsic = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
        val tmpIn: Allocation = Allocation.createFromBitmap(rs, inputBitmap)
        val tmpOut: Allocation = Allocation.createFromBitmap(rs, outputBitmap)
        theIntrinsic.setRadius(blurRadius)
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
