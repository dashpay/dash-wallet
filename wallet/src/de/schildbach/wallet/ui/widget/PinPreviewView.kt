package de.schildbach.wallet.ui.widget

import android.content.Context
import android.graphics.drawable.TransitionDrawable
import android.os.Handler
import android.util.AttributeSet
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.LinearLayout
import de.schildbach.wallet_test.R


class PinPreviewView(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {

    private var lastIndex = 0
    private var activeIndex = 0

    var mode: PinType = PinType.STANDARD
        set(value) {
            field = value
            clear()
        }

    private val pinItems = arrayListOf<View>()

    enum class PinType {
        STANDARD,   // standard pin - 4 digits
        EXTENDED    // extended pin - more than 4 digits
    }

    init {
        inflate(context, R.layout.pin_preview_view, this)
        lastIndex = childCount - 1
        for (i in 0..lastIndex) {
            pinItems.add(getChildAt(i))
        }
    }

    fun clear() {
        activeIndex = 0
        pinItems[lastIndex].setBackgroundResource(if (mode == PinType.EXTENDED) R.drawable.pin_item_more else R.drawable.pin_item)
        for (i in 0..lastIndex) {
            val pinItemViewBackground = pinItems[i].background as TransitionDrawable
            pinItemViewBackground.resetTransition()
        }
    }

    private fun setState(itemIndex: Int, active: Boolean) {
        val pinItemViewBackground = pinItems[itemIndex].background as TransitionDrawable
        pinItemViewBackground.isCrossFadeEnabled = true
        val durationMs = 100
        if (active) {
            pinItemViewBackground.startTransition(durationMs)
        } else {
            pinItemViewBackground.reverseTransition(durationMs)
        }
    }

    fun next() {
        if (activeIndex <= lastIndex) {
            setState(activeIndex, true)
            activeIndex++
        } else {
            if (mode == PinType.EXTENDED) {
                blinkLastItem()
                activeIndex++
            }
        }
    }

    fun prev() {
        if (activeIndex > 0) {
            activeIndex--
            if (activeIndex > lastIndex) {
                blinkLastItem()
            } else {
                setState(activeIndex, false)
            }
        }
    }


    private fun blinkLastItem() {
        val pinItemViewBackground = pinItems[lastIndex].background as TransitionDrawable
        pinItemViewBackground.resetTransition()
        Handler().postDelayed({ setState(lastIndex, true) }, 100)
    }

    fun shake() {
        val shakeAnimation = AnimationUtils.loadAnimation(context, R.anim.shake_pin)
        startAnimation(shakeAnimation)
    }
}
