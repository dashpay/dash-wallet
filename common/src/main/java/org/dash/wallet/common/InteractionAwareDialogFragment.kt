package org.dash.wallet.common

import android.app.Dialog
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

abstract class InteractionAwareDialogFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialogBuilder = AlertDialog.Builder(requireContext())
                .setView(initCustomView())

        val dialog = dialogBuilder.create()

        setCallback(dialog)
        return dialog
    }

    private fun setCallback(dialog: AlertDialog) {
        dialog.window!!.callback = UserInteractionAwareCallback(dialog.window!!.callback, requireActivity())
    }

    protected abstract fun initCustomView(): View

    protected fun imitateUserInteraction() {
        requireActivity().onUserInteraction()
    }
}