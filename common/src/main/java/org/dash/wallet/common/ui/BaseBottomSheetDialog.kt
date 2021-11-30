package org.dash.wallet.common.ui

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.dash.wallet.common.UserInteractionAwareCallback

open class BaseBottomSheetDialog(context: Context, private val lifecycle: Lifecycle) : BottomSheetDialog(context), LifecycleObserver {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(this)
        window?.callback = UserInteractionAwareCallback(
            window?.callback, context as Activity?
        )
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun stop() {
        dismiss()
        Log.e("TAG", "================================>>>> lifecycle owner STOPPED")
    }
}