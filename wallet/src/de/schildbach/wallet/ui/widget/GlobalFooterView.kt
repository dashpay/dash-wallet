/*
 * Copyright 2019 Dash Core Group
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

package de.schildbach.wallet.ui.widget

import android.app.Activity
import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.children
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.global_footer_view.view.*


class GlobalFooterView(context: Context, attrs: AttributeSet?) : ConstraintLayout(context, attrs) {

    var onFooterActionListener: OnFooterActionListener? = null

    init {
        inflate(context, R.layout.global_footer_view, this)
        home_button_view.setOnClickListener {
            onFooterActionListener?.onHomeClick()
        }
        goto_button_view.setOnClickListener {
            onFooterActionListener?.onGotoClick()
        }
        more_button_view.setOnClickListener {
            onFooterActionListener?.onMoreClick()
        }
    }

    companion object {
        fun encloseContentView(activity: Activity, contentViewResId: Int): GlobalFooterView {
            val globalFooterView = GlobalFooterView(activity, null)
            val contentView = activity.layoutInflater.inflate(contentViewResId, null)
            globalFooterView.addView(contentView)
            return globalFooterView
        }
    }

    override fun addView(child: View?, index: Int) {
        content_view.addView(child, index)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val proposedHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (height > proposedHeight) {
            onShowKeyboard()
        } else {
            onHideKeyboard()
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun onShowKeyboard() {
        expandContent(true)
    }

    private fun onHideKeyboard() {
        expandContent(false)
    }

    private fun expandContent(expand: Boolean) {
        for (child in children) {
            if (child.id != content_view.id) {
                child.visibility = if (expand) View.GONE else View.VISIBLE
            }
        }
        content_view.visibility = View.VISIBLE
        requestLayout()
    }

    fun activateHomeButton(active: Boolean) {
        home_button_view.isEnabled = !active
    }

    fun activateMoreButton(active: Boolean) {
        more_button_view.isEnabled = !active
    }

    fun activateGotoButton(active: Boolean) {
        goto_button_view.setImageResource(R.drawable.ic_goto_active)
    }

    interface OnFooterActionListener {
        fun onHomeClick()
        fun onGotoClick()
        fun onMoreClick()
    }
}
