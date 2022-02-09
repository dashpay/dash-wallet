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

package org.dash.wallet.integrations.crowdnode.ui

import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.integrations.crowdnode.R
import org.dash.wallet.integrations.crowdnode.databinding.DialogErrorBinding
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ErrorDialog : DialogFragment() {
    companion object {
        private const val MESSAGE_ARG = "message"
        private const val ACTION_BTN_TEXT = "action_btn_text"

        fun create(message: String, actionButtonText: String): ErrorDialog {
            val args = Bundle().apply {
                putString(MESSAGE_ARG, message)
                putString(ACTION_BTN_TEXT, actionButtonText)
            }
            return ErrorDialog().apply {
                arguments = args
            }
        }
    }

    private val binding by viewBinding(DialogErrorBinding::bind)
    private var onResultListener: ((Boolean?) -> Unit)? = null
    private var onActionListener: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setStyle(STYLE_NO_FRAME, R.style.FullScreenDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            requestFeature(Window.FEATURE_NO_TITLE)
        }

        return inflater.inflate(R.layout.dialog_error, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.subtitle.text = requireArguments().getString(MESSAGE_ARG)
        binding.actionBtn.text = requireArguments().getString(ACTION_BTN_TEXT)

        binding.actionBtn.setOnClickListener { onActionListener?.invoke() }
        binding.positiveBtn.setOnClickListener { onPositiveAction()  }
        binding.negativeBtn.setOnClickListener { onNegativeAction() }
    }

    private fun onPositiveAction() {
        onResultListener?.invoke(true)
        onResultListener = null
        dismiss()
    }

    private fun onNegativeAction() {
        onResultListener?.invoke(false)
        onResultListener = null
        dismiss()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onResultListener?.invoke(null)
        onResultListener = null
    }

    fun show(activity: FragmentActivity, onResult: (Boolean?) -> Unit, onAction: () -> Unit) {
        onResultListener = onResult
        onActionListener = onAction
        show(activity.supportFragmentManager, "crowdnode_error_dialog")
    }

    suspend fun showAsync(activity: FragmentActivity, onAction: () -> Unit): Boolean? {
        return suspendCancellableCoroutine { coroutine ->
            val onSuccess: (Boolean?) -> Unit = { result ->
                if (coroutine.isActive) {
                    coroutine.resume(result)
                }
            }

            try {
                show(activity, onSuccess, onAction)
            } catch(ex: Exception) {
                if (coroutine.isActive) {
                    coroutine.resumeWithException(ex)
                }
            }
        }
    }
}