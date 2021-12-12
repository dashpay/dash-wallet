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
package org.dash.wallet.integration.coinbase_integration.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.fragment.app.DialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.integration.coinbase_integration.R
import org.dash.wallet.integration.coinbase_integration.databinding.DialogCoinbaseErrorBinding

class CoinBaseErrorDialog : DialogFragment() {
    private val binding by viewBinding(DialogCoinbaseErrorBinding::bind)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            requestFeature(Window.FEATURE_NO_TITLE)
        }
        return inflater.inflate(R.layout.dialog_coinbase_error, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setOrHideIfEmpty(binding.coinbaseDialogTitle, "title")
        setOrHideIfEmpty(binding.coinbaseDialogMessage, "message")
        setOrHideIfEmpty(binding.coinbaseDialogIcon, "image")
        setOrHideIfEmpty(binding.coinbaseDialogPositiveButton, "positive_text")
        setOrHideIfEmpty(binding.coinbaseDialogNegativeButton, "negative_text")

        binding.coinbaseDialogPositiveButton.setOnClickListener {
            val defaultBrowser = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
            defaultBrowser.data = Uri.parse("https://www.coinbase.com/")
            startActivity(defaultBrowser)
        }

        binding.coinbaseDialogNegativeButton.setOnClickListener {
            dismiss()
        }
    }

    private fun setOrHideIfEmpty(view: View, argKey: String) {
        if (arguments == null) {
            view.visibility = View.GONE
            return
        }
        arguments?.getInt(argKey)?.let {
            if (it != 0) {
                when (view) {
                    is TextView -> view.setText(it)
                    is ImageView -> view.setImageResource(it)
                }
                view.visibility = View.VISIBLE
            } else {
                view.visibility = View.GONE
            }
        }
    }
    companion object {

        fun newInstance(
            @StringRes title: Int,
            @StringRes message: Int,
            @DrawableRes image: Int,
            @StringRes positiveButtonText: Int,
            @StringRes negativeButtonText: Int
        ): CoinBaseErrorDialog {
            val args = Bundle().apply {
                putInt("title", title)
                putInt("message", message)
                putInt("image", image)
                putInt("positive_text", positiveButtonText)
                putInt("negative_text", negativeButtonText)
            }
            return CoinBaseErrorDialog().apply {
                arguments = args
            }
        }
    }
}
