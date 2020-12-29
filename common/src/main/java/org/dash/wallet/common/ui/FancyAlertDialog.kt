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
import androidx.annotation.IntegerRes
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import kotlinx.android.synthetic.main.fancy_alert_dialog.*
import org.dash.wallet.common.R

class FancyAlertDialog : DialogFragment() {

    companion object {
        fun newInstance(@IntegerRes title: Int, @IntegerRes message: Int, @IntegerRes image: Int = 0): FancyAlertDialog {
            val args = Bundle().apply {
                putInt("title", title)
                putInt("message", message)
                putInt("image", image)
            }
            return FancyAlertDialog().apply {
                arguments = args
            }
        }
    }

    private lateinit var sharedViewModel: FancyAlertDialogViewModel

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        // Set transparent background and no title
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            requestFeature(Window.FEATURE_NO_TITLE)
        }
        return inflater.inflate(R.layout.fancy_alert_dialog, container)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        positive_button.setOnClickListener {
            dismiss()
            sharedViewModel.onPositiveButtonClick.call()
        }
        negative_button.setOnClickListener {
            dismiss()
            sharedViewModel.onPositiveButtonClick.call()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        arguments?.apply {
            title.setText(getInt("title"))
            message.setText(getInt("message"))
            image.setImageResource(getInt("image"))
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        sharedViewModel = activity?.run {
            ViewModelProvider(this)[FancyAlertDialogViewModel::class.java]
        } ?: throw IllegalStateException("Invalid Activity")
    }
}