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
import androidx.lifecycle.ViewModelProvider
import kotlinx.android.synthetic.main.fancy_alert_dialog.*
import org.dash.wallet.common.R

open class FancyAlertDialog : DialogFragment() {

    enum class Type {
        INFO,
        PROGRESS
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

    private lateinit var sharedViewModel: FancyAlertDialogViewModel
    private val type by lazy {
        Type.valueOf(arguments!!.getString("type")!!)
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
//            view.findViewById<View>(R.id.default_content).visibility = View.GONE
            inflater.inflate(customContentViewResId, view.findViewById(R.id.custom_content))
        }
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val titleStr = try {
            requireArguments().getString("title")
        } catch (ex: ClassCastException) {
            null
        }
        if (titleStr != null) {
            title.text = titleStr
        } else {
            setOrHideIfEmpty(title, "title")
        }
        val messageStr = try {
            requireArguments().getString("message")
        } catch (ex: ClassCastException) {
            null
        }
        if (messageStr != null) {
            message.text = HtmlCompat.fromHtml(messageStr, HtmlCompat.FROM_HTML_MODE_COMPACT)
        } else {
            setOrHideIfEmpty(message, "message")
        }
        setOrHideIfEmpty(image, "image")
        setOrHideIfEmpty(positive_button, "positive_text")
        setOrHideIfEmpty(negative_button, "negative_text")

        when (type) {
            Type.INFO -> {
                setupInfo()
            }
            Type.PROGRESS -> {
                setupProgress()
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
        progress.visibility = View.GONE
        positive_button.setOnClickListener {
            dismiss()
            sharedViewModel.onPositiveButtonClick.call()
        }
        negative_button.setOnClickListener {
            dismiss()
            sharedViewModel.onPositiveButtonClick.call()
        }
    }

    private fun setupProgress() {
        image.visibility = View.GONE
        positive_button.visibility = View.GONE
        negative_button.visibility = View.GONE
        isCancelable = false
    }

    override fun onStart() {
        super.onStart()
        dialog?.apply {
            window?.setLayout(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            if (type == Type.PROGRESS) {
                setCancelable(false)
                setCanceledOnTouchOutside(false)
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        sharedViewModel = activity?.run {
            ViewModelProvider(this)[FancyAlertDialogViewModel::class.java]
        } ?: throw IllegalStateException("Invalid Activity")
    }
}