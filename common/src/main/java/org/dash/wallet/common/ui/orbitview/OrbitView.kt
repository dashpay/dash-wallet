/*
    MIT License

    Copyright (c) 2020 Samuel Barbosa

    Permission is hereby granted, free of charge, to any person obtaining a copy
    of this software and associated documentation files (the "Software"), to deal
    in the Software without restriction, including without limitation the rights
    to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
    copies of the Software, and to permit persons to whom the Software is
    furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all
    copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
    IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
    FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
    AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
    LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
    OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
    SOFTWARE.
 */
package org.dash.wallet.common.ui.orbitview

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.core.view.children
import org.dash.wallet.common.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * @author Samuel Barbosa
 */
class OrbitView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var paint: Paint? = null
    private var color: Int? = null
    private val startAnimationsDelay = 333
    private var animated = false

    init {
        setWillNotDraw(false)
        context.theme.obtainStyledAttributes(attrs, R.styleable.OrbitView, 0, 0).apply {
            try {
                color = getColor(R.styleable.OrbitView_orbitColor, Color.GREEN)
                color?.let {
                    paint = Paint(Paint.ANTI_ALIAS_FLAG)
                    paint?.apply {
                        style = Paint.Style.STROKE
                        color = it
                        strokeWidth = getDimensionPixelSize(
                            R.styleable.OrbitView_orbitWidth,
                            2
                        ).toFloat()
                    }
                }
            } finally {
                recycle()
            }
        }
    }

    private fun setViewAngle(view: PlanetView, angle: Int, centerX: Float, centerY: Float, r: Int) {
        view.x = centerX + cos(angle.toFloat() * Math.PI.toFloat() / 180f) * r
        view.y = centerY + sin(angle.toFloat() * Math.PI.toFloat() / 180f) * r
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        color?.let {
            paint?.let { it1 ->
                canvas?.drawCircle(
                    (width / 2).toFloat(),
                    (height / 2).toFloat(), (width / 2).toFloat(), it1
                )
            }
        }

        val r = width / 2


        val planets = children.filterIsInstance<PlanetView>()
        val anims = arrayListOf<ValueAnimator>()

        planets.forEach {
            val centerX = (width / 2).toFloat() - (it.width / 2)
            val centerY = (height / 2).toFloat() - (it.height / 2)

            it.x = centerX
            it.y = centerY

            if (!animated) {
                val anim = ValueAnimator.ofInt(it.fromAngle, it.toAngle)
                    .setDuration(it.orbitDuration.toLong())

                anim.addUpdateListener { animVal ->
                    val angle = animVal.animatedValue as Int
                    setViewAngle(it, angle, centerX, centerY, r)
                }

                anim.interpolator = it.animInterpolator
                anim.startDelay = it.animStartDelay.toLong() - startAnimationsDelay
                anim.repeatCount = it.repeatCount
                anims.add(anim)
            } else {
                setViewAngle(it, it.toAngle, centerX, centerY, r)
            }

        }

        if (!animated) {
            handler.postDelayed({
                anims.forEach { it.start() }
                animated = true
            }, startAnimationsDelay.toLong())
        }
    }

}