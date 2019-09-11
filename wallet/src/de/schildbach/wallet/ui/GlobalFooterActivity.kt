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

package de.schildbach.wallet.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import de.schildbach.wallet.ui.widget.GlobalFooterView
import de.schildbach.wallet_test.R
import android.opengl.ETC1.getHeight
import androidx.core.content.ContextCompat.getSystemService
import android.app.ActivityManager.TaskDescription




@SuppressLint("Registered")
open class GlobalFooterActivity : AppCompatActivity() {

    lateinit var globalFooterView: GlobalFooterView

    private var keyboardListenersAttached = false

    private val keyboardLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
//        val r = Rect()
//        globalFooterView!!.getWindowVisibleDisplayFrame(r)
//
//        val heightDiff = view.getRootView().getHeight() - (r.bottom - r.top)
//        if (heightDiff > 100) {
//            //enter your code here
//        } else {
//            //enter code for hid
//        }


//        val heightDiff = globalFooterView!!.rootView.height - globalFooterView!!.height
//        val contentViewTop = window.findViewById<View>(Window.ID_ANDROID_CONTENT).height
//        val broadcastManager = LocalBroadcastManager.getInstance(this@GlobalFooterActivity)
//
//        if (heightDiff <= contentViewTop) {
//            onHideKeyboard()
//
//            val intent = Intent("KeyboardWillHide")
//            broadcastManager.sendBroadcast(intent)
//        } else {
//            val keyboardHeight = heightDiff - contentViewTop
//            onShowKeyboard(keyboardHeight)
//
//            val intent = Intent("KeyboardWillShow")
//            intent.putExtra("KeyboardHeight", keyboardHeight)
//            broadcastManager.sendBroadcast(intent)
//        }
    }

    fun setContentViewWithFooter(layoutResId: Int) {
        globalFooterView = GlobalFooterView.encloseContentView(this, layoutResId)
        setContentView(globalFooterView)

//        attachKeyboardListeners()
    }

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        globalFooterView = findViewById(R.id.global_footer_view)
    }

//    private fun attachKeyboardListeners() {
//        if (keyboardListenersAttached) {
//            return
//        }
//        globalFooterView.viewTreeObserver.addOnGlobalLayoutListener(keyboardLayoutListener)
//        keyboardListenersAttached = true
//    }
//
//    override fun onDestroy() {
//        super.onDestroy()
//
//        if (keyboardListenersAttached) {
//            globalFooterView!!.viewTreeObserver.removeOnGlobalLayoutListener(keyboardLayoutListener)
//        }
//    }
//
//    private fun onShowKeyboard(keyboardHeight: Int) {
//        globalFooterView!!.findViewById<View>(R.id.goto_button_view).visibility = View.GONE
//    }
//
//    private fun onHideKeyboard() {
//        globalFooterView!!.findViewById<View>(R.id.goto_button_view).visibility = View.VISIBLE
//    }
}
