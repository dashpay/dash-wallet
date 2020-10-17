package de.schildbach.wallet.ui.dashpay.widget

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.util.AttributeSet
import android.util.Log
import android.view.View
import com.ortiz.touchview.TouchImageView


class CircleCropTouchImageView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyle: Int = 0) : TouchImageView(context, attrs, defStyle) {

    var blurredBitmap: Bitmap? = null
    var calculatedMinZoom = false

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        val vW = measuredWidth
        val vH = measuredHeight

        val imgScaleRatio = 0.7f
        val imgVerticalDisplacement = 0.5f
        val bitmap = (drawable as BitmapDrawable?)?.bitmap
        if (bitmap != null) {
            //Create blurred bitmap for the background
            if (blurredBitmap == null) {
                //TODO: Set blur times as argument of the blurring function
                blurredBitmap = blur(this.context, bitmap)
                blurredBitmap = blur(this.context, blurredBitmap!!)
                //blurredBitmap = blur(this.context, blurredBitmap!!)
            }

            val bW = blurredBitmap!!.width
            val bH = blurredBitmap!!.height

            //Set initial and min zoom
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
            val halfHeight = height / 2f

            //Capture the loaded picture ????
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val newCanvas = Canvas(bmp)
            super.draw(newCanvas)

            val path = Path()
            path.addCircle(halfWidth, vH * imgVerticalDisplacement, halfWidth * imgScaleRatio,
                    Path.Direction.CCW)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            val colorMatrix = ColorMatrix()
            val colorScale = 0.9f
            colorMatrix.setScale(colorScale, colorScale, colorScale, 1f)
            paint.colorFilter = ColorMatrixColorFilter(colorMatrix)

            //Center Crop
            val blurredMatrix = Matrix()

            var xScale: Float
            var yScale: Float

            if (bW > bH || bW == bH) {
                yScale = if (bW > vW) {
                    bH.toFloat() / vH.toFloat()
                } else {
                    vH.toFloat() / bH.toFloat()
                }
                xScale = yScale
                val postBw = bW * xScale
                if (postBw < vW) {
                    val s = vW / postBw
                    yScale *= s
                    xScale *= s
                }
            } else {
                xScale = if (bH > vH) {
                    bW.toFloat() / vW.toFloat()
                } else {
                    vW.toFloat() / bW.toFloat()
                }
                yScale = xScale
                val postBh = bH * yScale
                if (postBh < vH) {
                    val s = vH / postBh
                    yScale *= s
                    xScale *= s
                }
            }

            val translationX = (bW * xScale / 2f) - (vW / 2f)
            blurredMatrix.apply {
                setScale(xScale, yScale)
                postTranslate(-translationX, 0f)
            }
            canvas.drawBitmap(blurredBitmap!!, blurredMatrix, paint)

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
