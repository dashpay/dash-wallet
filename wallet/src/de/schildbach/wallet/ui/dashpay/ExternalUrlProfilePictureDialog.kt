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

package de.schildbach.wallet.ui.dashpay

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import de.schildbach.wallet.ui.ExternalUrlProfilePictureViewModel
import de.schildbach.wallet.util.KeyboardUtil
import de.schildbach.wallet_test.R


class ExternalUrlProfilePictureDialog : DialogFragment() {

    companion object {

        private const val ARG_INITIAL_URL = "arg_initial_url"

        @JvmStatic
        fun newInstance(initialUrl: String?): ExternalUrlProfilePictureDialog {
            val dialog = ExternalUrlProfilePictureDialog()
            dialog.arguments = Bundle().apply {
                putString(ARG_INITIAL_URL, initialUrl)
            }
            return dialog
        }
    }

    private val initialUrl by lazy {
        arguments?.getString(ARG_INITIAL_URL)
    }

    private lateinit var customView: View
    private lateinit var edit: EditText
    private lateinit var urlPreviewPane: View
    private lateinit var urlPreview: ImageView
    private lateinit var positiveButton: Button
    private lateinit var neutralButton: Button

    private lateinit var sharedViewModel: ExternalUrlProfilePictureViewModel

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialogBuilder = AlertDialog.Builder(requireContext())
                .setTitle("External URL")
                .setPositiveButton(R.string.button_ok) { _, _ ->
                    sharedViewModel.confirm()
                    KeyboardUtil.hideKeyboard(requireContext(), edit)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    KeyboardUtil.hideKeyboard(requireContext(), edit)
                }
                .setNeutralButton("clear", null)
                .setView(initCustomView())

        val dialog = dialogBuilder.create()
        dialog.setOnShowListener {
            positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.isEnabled = false
            neutralButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            neutralButton.setOnClickListener {
                edit.text = null
            }
            if (edit.length() == 0) {
                neutralButton.visibility = View.GONE
            }
            if (initialUrl != null) {
                edit.setText(initialUrl)
            }
        }
        return dialog
    }

    @SuppressLint("InflateParams")
    private fun initCustomView(): View {
        customView = requireActivity().layoutInflater.inflate(R.layout.dialog_input_text, null)
        edit = customView.findViewById(R.id.input)
        urlPreviewPane = customView.findViewById(R.id.url_preview_pane)
        urlPreview = customView.findViewById(R.id.url_preview)
        edit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                urlPreviewPane.visibility = View.GONE
                neutralButton.visibility = if (edit.length() > 0) View.VISIBLE else View.GONE
                val pictureUrl = edit.text.trim().toString()
                if (pictureUrl.isEmpty()) {
                    return
                }
                loadUrl(pictureUrl)
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        return customView
    }

    private fun loadUrl(pictureUrlBase: String) {
        val googleDrivePreview = "https://drive.google.com/file/d/"
        val googleDrivePublic = "http://drive.google.com/uc?export=view&id="
        val pictureUrl = if (pictureUrlBase.startsWith(googleDrivePreview)) {
            pictureUrlBase.replace(googleDrivePreview, googleDrivePublic).replace("/view", "")
        } else {
            pictureUrlBase
        }
        Glide.with(requireContext())
                .load(pictureUrl)
                .override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
//                        .listener(object : RequestListener<Drawable> {
//                            override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>?, isFirstResource: Boolean): Boolean {
//                                urlPreviewPane.visibility = View.GONE
//                                positiveButton.isEnabled = false
//                                return false
//                            }
//                            override fun onResourceReady(resource: Drawable?, model: Any?, target: Target<Drawable>?, dataSource: DataSource?, isFirstResource: Boolean): Boolean {
//                                urlPreviewPane.visibility = View.VISIBLE
//                                positiveButton.isEnabled = true
//                                return false
//                            }
//                        })
//                        .into(urlPreview)
                .into(object : CustomTarget<Drawable?>() {
                    override fun onResourceReady(@NonNull resource: Drawable, @Nullable transition: Transition<in Drawable?>?) {
                        urlPreviewPane.visibility = View.VISIBLE
                        positiveButton.error = null
                        positiveButton.isEnabled = true
                        val bitmap: Bitmap = (resource as BitmapDrawable).bitmap
                        urlPreview.setImageBitmap(bitmap)
                        sharedViewModel.bitmapCache = bitmap
                        sharedViewModel.externalUrl = Uri.parse(pictureUrl)
                    }

                    override fun onLoadCleared(@Nullable placeholder: Drawable?) {

                    }

                    override fun onLoadFailed(@Nullable errorDrawable: Drawable?) {
                        super.onLoadFailed(errorDrawable)
                        urlPreviewPane.visibility = View.GONE
                        positiveButton.isEnabled = false
                        sharedViewModel.bitmapCache = null
                        sharedViewModel.externalUrl = null
                        Toast.makeText(requireContext(), "Failed to Download Image! Please try again later.", Toast.LENGTH_SHORT).show()
                    }
                })
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        sharedViewModel = activity?.run {
            ViewModelProvider(this)[ExternalUrlProfilePictureViewModel::class.java]
        } ?: throw IllegalStateException("Invalid Activity")
    }
}
