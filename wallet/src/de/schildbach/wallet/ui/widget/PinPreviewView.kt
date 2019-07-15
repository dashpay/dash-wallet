package de.schildbach.wallet.ui.widget

import android.content.Context
import android.graphics.drawable.TransitionDrawable
import android.os.Handler
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.ViewCompat
import de.schildbach.wallet_test.R


class PinPreviewView(context: Context, attrs: AttributeSet) : FrameLayout(context, attrs) {

    var constraintLayout: ConstraintLayout
    val pin = arrayListOf<Int>()
    var activeIndex = 0

    var mode: PinType = PinType.EXTENDED

    enum class PinType {
        STANDARD,   // standard pin - 4 digits
        EXTENDED    // extended pin - more than 4 digits
    }

    init {
        inflate(context, R.layout.pin_preview_view, this)
        constraintLayout = getChildAt(0) as ConstraintLayout
        init()
    }

    fun init() {
        for (i in 0..2) {
            addPinItem(false)
        }
        addPinItem(mode == PinType.EXTENDED)
        adjustConstraints()
    }

    fun next() {
        if (activeIndex < 3 || mode == PinType.EXTENDED) {
            val lastChildIndex = constraintLayout.childCount - 1
            if (activeIndex > lastChildIndex) {
                blinkLastItem()
            } else {
                setState(activeIndex, true)
            }
            activeIndex++
        }
    }

    fun prev() {
        if (activeIndex > 0) {
            activeIndex--
            val lastChildIndex = constraintLayout.childCount - 1
            if (activeIndex > lastChildIndex) {
                blinkLastItem()
            } else {
                setState(activeIndex, false)
            }
        }
    }

    private fun setState(itemIndex: Int, active: Boolean) {
        val pinItemView = constraintLayout.getChildAt(itemIndex)
        val pinItemViewBackground = pinItemView.background as TransitionDrawable
        if (active) {
            pinItemViewBackground.startTransition(100)
        } else {
            pinItemViewBackground.reverseTransition(100)
        }
    }

    private fun blinkLastItem() {
        val lastChildIndex = constraintLayout.childCount - 1
        val pinItemView = constraintLayout.getChildAt(lastChildIndex)
        val pinItemViewBackground = pinItemView.background as TransitionDrawable
        pinItemViewBackground.resetTransition()
        Handler().postDelayed({ setState(lastChildIndex, true) }, 100)
    }

    fun removePinView() {
        if (constraintLayout.childCount > 4) {
            constraintLayout.removeViewAt(constraintLayout.childCount - 1)
            adjustConstraints()
        }
    }

    private fun addPinItem(extendedPinIcon: Boolean): FrameLayout {
        val viewItem = FrameLayout(context)
        viewItem.setBackgroundResource(if (extendedPinIcon) R.drawable.pin_item_extended else R.drawable.pin_item_back)
        viewItem.id = ViewCompat.generateViewId()
        (viewItem.background as TransitionDrawable).isCrossFadeEnabled = true

        constraintLayout.addView(viewItem)

        val layoutParams = viewItem.layoutParams as ConstraintLayout.LayoutParams
        layoutParams.run {
            width = 0
            height = 0
            val marginDp = dpToPx(8.0f).toInt()
            setMargins(marginDp, 0, marginDp, 0)
        }

        return viewItem
    }

    private fun adjustConstraints() {
        val constraintSet = ConstraintSet()
        constraintSet.clone(constraintLayout)

        val viewIds = arrayListOf<Int>()

        for (i in 0 until constraintLayout.childCount) {
            val itemId = constraintLayout.getChildAt(i).id
            viewIds.add(itemId)

            // app:layout_constraintDimensionRatio="1:1"
            constraintSet.setDimensionRatio(itemId, "1:1")
            // app:layout_constraintTop_toTopOf="parent"
            constraintSet.connect(itemId, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, 0)
            // app:layout_constraintBottom_toBottomOf="parent"
            constraintSet.connect(itemId, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, 0)

            if (i == 0) {
                constraintSet.connect(itemId, ConstraintSet.LEFT, ConstraintSet.PARENT_ID, ConstraintSet.LEFT)
            } else {
                val previousItem = constraintLayout.getChildAt(i - 1)
                constraintSet.connect(itemId, ConstraintSet.LEFT, previousItem.id, ConstraintSet.RIGHT)
                if (i == constraintLayout.childCount - 1) {
                    constraintSet.connect(itemId, ConstraintSet.RIGHT, ConstraintSet.PARENT_ID, ConstraintSet.RIGHT)
                }
            }
        }

        constraintSet.createHorizontalChain(ConstraintSet.PARENT_ID, ConstraintSet.LEFT, ConstraintSet.PARENT_ID, ConstraintSet.RIGHT, viewIds.toIntArray(), null, ConstraintSet.CHAIN_SPREAD)
        constraintSet.applyTo(constraintLayout)
    }

    private fun tmp() {
//        val pinItemView = findViewById<View>(R.id.pin_item_back)
//        val pinItemViewBackground = pinItemView.background as TransitionDrawable
//        pinItemViewBackground.isCrossFadeEnabled = true
//        if (pinItemView.tag == null) {
//            pinItemViewBackground.startTransition(100)
//            pinItemView.tag = true
//        } else {
//            pinItemViewBackground.reverseTransition(100)
//            pinItemView.tag = null
//        }
    }

    fun dpToPx(dp: Float): Float {
        return dp * context.resources.displayMetrics.density
    }
}
