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

class KeyboardUtil {
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

    private var rootView: View? = null
    private var decorView: View? = null
    private var defaultPadding: Int = 0

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        val root = rootView
        val decor = decorView

        if (root == null || decor == null) {
            return@OnGlobalLayoutListener
        }

        val rect = Rect()
        decor.getWindowVisibleDisplayFrame(rect)

        val displayHeight = decor.context.resources.displayMetrics.heightPixels
        val diff = displayHeight - rect.bottom

        if (diff > 100) { // assume the keyboard is showing
            root.setPadding(0, 0, 0, diff + defaultPadding)
        } else {
            root.setPadding(0, 0, 0, defaultPadding)
        }
    }

    fun enableAdjustLayout(window: Window, rootView: View) {
        this.decorView = window.decorView
        this.rootView = rootView
        this.defaultPadding = rootView.paddingBottom
        decorView!!.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
    }

    fun disableAdjustLayout() {
        decorView?.viewTreeObserver?.removeOnGlobalLayoutListener(layoutListener)
    }
}
