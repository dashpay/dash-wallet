/*
 * Copyright 2020 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui.dashpay.widget

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.util.AttributeSet
import androidx.core.net.toFile
import com.ortiz.touchview.TouchImageView
import java.io.FileOutputStream


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

    fun saveToFile(imageFile: Uri) {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val newCanvas = Canvas(bmp)
        super.draw(newCanvas)
        bmp.compress(Bitmap.CompressFormat.JPEG, 100, FileOutputStream(imageFile.toFile()))
        bmp.recycle()
    }

}
