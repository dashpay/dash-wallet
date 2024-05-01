package org.dash.wallet.common.ui.components

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView

class ComposeHostFrameLayout
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
    ) : FrameLayout(context, attrs, defStyleAttr) {
        private var composeView: ComposeView? = null

        fun setContent(content: @Composable () -> Unit) {
            if (composeView == null) {
                composeView = ComposeView(context)
                addView(composeView)
            }
            composeView?.setContent(content)
        }

        override fun removeAllViews() {
            composeView = null
            super.removeAllViews()
        }
    }
