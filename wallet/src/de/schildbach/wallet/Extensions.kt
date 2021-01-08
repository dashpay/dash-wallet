package de.schildbach.wallet

import android.content.Context
import android.content.ContextWrapper
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleOwner

//
// https://stackoverflow.com/questions/49075413/easy-way-to-get-current-activity-fragment-lifecycleowner-from-within-view
//

fun Context.fragmentActivity(): FragmentActivity? {
    var curContext = this
    var maxDepth = 20
    while (--maxDepth > 0 && curContext !is FragmentActivity) {
        curContext = (curContext as ContextWrapper).baseContext
    }
    return if(curContext is FragmentActivity)
        curContext
    else
        null
}

fun Context.lifecycleOwner(): LifecycleOwner? {
    var curContext = this
    var maxDepth = 20
    while (maxDepth-- > 0 && curContext !is LifecycleOwner) {
        curContext = (curContext as ContextWrapper).baseContext
    }
    return if (curContext is LifecycleOwner) {
        curContext
    } else {
        null
    }
}