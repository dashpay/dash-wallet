package org.dash.wallet.common.ui

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.dash.wallet.common.R
import org.dash.wallet.common.UserInteractionAwareCallback

open class BaseBottomSheetDialog(context: Context) : BottomSheetDialog(context, R.style.BottomSheetDialog) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setCancelable(true)
        setCanceledOnTouchOutside(true)
        window?.callback = UserInteractionAwareCallback(window?.callback, context.getActivity())
    }

    override fun onStart() {
        super.onStart()
        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}