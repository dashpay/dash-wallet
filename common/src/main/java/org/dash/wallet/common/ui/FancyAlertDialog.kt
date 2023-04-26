/*
 * Copyright 2020 Dash Core Group
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

package org.dash.wallet.common.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.Window
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.IntegerRes
import androidx.annotation.StringRes
import androidx.core.text.HtmlCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import org.dash.wallet.common.R
import org.dash.wallet.common.UserInteractionAwareCallback
import org.dash.wallet.common.databinding.FancyAlertDialogBinding

@Deprecated("Use AdaptiveDialog")
open class FancyAlertDialog : DialogFragment() {
    var onFancyAlertButtonsClickListener: FancyAlertButtonsClickListener? = null
    private val binding by viewBinding(FancyAlertDialogBinding::bind)

    enum class Type {
        INFO,
        PROGRESS,
        ACTION
    }

    companion object {

        fun createBaseArguments(type: Type, @DrawableRes image: Int,
                                @StringRes positiveButtonText: Int = 0, @StringRes negativeButtonText: Int = 0): Bundle {
            return Bundle().apply {
                putString("type", type.name)
                putInt("image", image)
                putInt("positive_text", positiveButtonText)
                putInt("negative_text", negativeButtonText)
            }
        }

        fun newInstance(@StringRes title: Int, @StringRes message: Int, @DrawableRes image: Int,
                        @StringRes positiveButtonText: Int, @StringRes negativeButtonText: Int): FancyAlertDialog {

            return FancyAlertDialog().apply {
                arguments = createBaseArguments(Type.INFO, image, positiveButtonText, negativeButtonText)
                        .apply {
                            putInt("title", title)
                            putInt("message", message)
                        }
            }
        }

        fun newInstance(title: String, messageHtml: String, @DrawableRes image: Int,
                        @StringRes positiveButtonText: Int, @StringRes negativeButtonText: Int): FancyAlertDialog {
            return FancyAlertDialog().apply {
                arguments = createBaseArguments(Type.INFO, image, positiveButtonText, negativeButtonText)
                        .apply {
                            putString("title", title)
                            putString("message", messageHtml)
                        }
            }
        }

        @JvmStatic
        fun newInstance(title: String, message: String): FancyAlertDialog {
            return FancyAlertDialog().apply {
                arguments = createBaseArguments(Type.INFO, 0, 0, 0)
                        .apply {
                            putString("title", title)
                            putString("message", message)
                        }
            }
        }

        fun showInfo(fragmentManager: FragmentManager, @StringRes title: Int, @StringRes message: Int, @DrawableRes image: Int): FancyAlertDialog {
            return FancyAlertDialog().apply {
                arguments = createBaseArguments(Type.INFO, image, 0, 0)
                        .apply {
                            putInt("title", title)
                            putInt("message", message)
                        }
                show(fragmentManager, null)
            }
        }

        @JvmStatic
        fun newAction(@StringRes title: Int, @StringRes positiveButtonText: Int, @StringRes negativeButtonText: Int): FancyAlertDialog {
            val args = Bundle().apply {
                putString("type", Type.ACTION.name)
                putInt("title", title)
                putInt("positive_text", positiveButtonText)
                putInt("negative_text", negativeButtonText)
            }
            return FancyAlertDialog().apply {
                arguments = args
            }
        }

        @JvmStatic
        fun newProgress(@StringRes title: Int, @StringRes message: Int = 0): FancyAlertDialog {
            return FancyAlertDialog().apply {
                arguments = createBaseArguments(Type.PROGRESS, 0, 0, 0)
                        .apply {
                            putInt("title", title)
                            putInt("message", message)
                        }
            }
        }
    }

    private val type by lazy {
        Type.valueOf(requireArguments().getString("type")!!)
    }

    @IntegerRes
    open val customContentViewResId: Int = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        // Set transparent background and no title
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            requestFeature(Window.FEATURE_NO_TITLE)
        }
        val view = inflater.inflate(R.layout.fancy_alert_dialog, container)
        if (customContentViewResId != 0) {
            view.findViewById<View>(R.id.default_content).visibility = View.GONE
            inflater.inflate(customContentViewResId, view.findViewById(R.id.custom_content))
        }
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val titleObj = requireArguments().get("title")
        if (titleObj is String) {
            binding.title.text = titleObj
        } else {
            setOrHideIfEmpty(binding.title, "title")
        }
        val messageObj = requireArguments().get("message")
        if (messageObj is String) {
            binding.message.text = HtmlCompat.fromHtml(messageObj, HtmlCompat.FROM_HTML_MODE_COMPACT)
        } else {
            setOrHideIfEmpty(binding.message, "message")
        }
        setOrHideIfEmpty(binding.image, "image")
        setOrHideIfEmpty(binding.positiveButton, "positive_text")
        setOrHideIfEmpty(binding.negativeButton, "negative_text")

        binding.buttonSpace.visibility = if (binding.positiveButton.visibility == View.VISIBLE
            && binding.negativeButton.visibility == View.VISIBLE) View.VISIBLE else View.GONE

        when (type) {
            Type.INFO -> {
                setupInfo()
            }
            Type.PROGRESS -> {
                setupProgress()
            }
            Type.ACTION -> {
                setupAction()
            }
        }
    }

    private fun setOrHideIfEmpty(view: View, argKey: String) {
        val resId = requireArguments().getInt(argKey)
        if (resId != 0) {
            when (view) {
                is TextView -> view.setText(resId)
                is ImageView -> view.setImageResource(resId)
            }
            view.visibility = View.VISIBLE
        } else {
            view.visibility = View.GONE
        }
    }

    private fun setupInfo() {
        binding.progress.visibility = View.GONE
        binding.image.visibility = View.VISIBLE
        binding.positiveButton.setOnClickListener {
            dismiss()
            onFancyAlertButtonsClickListener?.onPositiveButtonClick()
        }
        binding.negativeButton.setOnClickListener {
            dismiss()
            onFancyAlertButtonsClickListener?.onPositiveButtonClick()
        }
    }

    private fun setupAction() {
        binding.progress.visibility = View.GONE
        binding.image.visibility = View.GONE
        binding.positiveButton.setOnClickListener {
            dismiss()
            onFancyAlertButtonsClickListener?.onPositiveButtonClick()
        }
        binding.negativeButton.setOnClickListener {
            dismiss()
            onFancyAlertButtonsClickListener?.onNegativeButtonClick()
        }
    }

    private fun setupProgress() {
        binding.progress.visibility = View.VISIBLE
        binding.image.visibility = View.GONE
        binding.positiveButton.visibility = View.GONE
        binding.negativeButton.visibility = View.GONE
        isCancelable = false
    }

    override fun onStart() {
        super.onStart()
        dialog?.apply {
            window?.apply {
                setLayout(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                callback = UserInteractionAwareCallback(this.callback, requireActivity())
            }
            if (type == Type.PROGRESS) {
                setCancelable(false)
                setCanceledOnTouchOutside(false)
            }
        }
    }

    interface FancyAlertButtonsClickListener {
        fun onPositiveButtonClick()
        fun onNegativeButtonClick()
    }
}
