/*
 * Copyright 2022 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.dash.wallet.common.util

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.inputmethod.InputMethodManager

class KeyboardUtil(window: Window, private val rootView: View) {
    companion object {
        fun showSoftKeyboard(context: Context?, view: View?) {
            if (context == null || view == null) {
                return
            }

            if (view.requestFocus()) {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                val isShowing = imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)

                if (!isShowing) {
                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .toggleSoftInput(
                            InputMethodManager.SHOW_FORCED,
                            InputMethodManager.HIDE_IMPLICIT_ONLY
                        )
                }
            }
        }

        fun hideKeyboard(context: Context?, view: View?) {
            if (context == null || view == null) {
                return
            }

            val keyboard = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            keyboard.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    private var decorView: View? = null
    private var defaultPadding: Int = 0
    private var onKeyboardShownChanged: ((Boolean) -> Unit)? = null
    private var adjustKeyboard: Boolean = false

    var isKeyboardShown: Boolean = false
        private set(value) {
            if (field != value) {
                field = value
                onKeyboardShownChanged?.invoke(value)
            }
        }

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        val decor = decorView ?: return@OnGlobalLayoutListener

        val rect = Rect()
        decor.getWindowVisibleDisplayFrame(rect)

        val displayHeight = decor.context.resources.displayMetrics.heightPixels
        val diff = displayHeight - rect.bottom

        if (diff > 100) { // assume the keyboard is showing
            isKeyboardShown = true

            if (adjustKeyboard) {
                rootView.setPadding(0, 0, 0, diff + defaultPadding)
            }
        } else {
            isKeyboardShown = false

            if (adjustKeyboard) {
                rootView.setPadding(0, 0, 0, defaultPadding)
            }
        }
    }

    init {
        this.decorView = window.decorView
        decorView?.viewTreeObserver?.addOnGlobalLayoutListener(layoutListener)
    }

    fun enableAdjustLayout() {
        requireNotNull(decorView)
        defaultPadding = rootView.paddingBottom
        adjustKeyboard = true
    }

    fun disableAdjustLayout() {
        adjustKeyboard = false
    }

    fun setOnKeyboardShownChanged(listener: (Boolean) -> Unit) {
        onKeyboardShownChanged = listener
    }
}
