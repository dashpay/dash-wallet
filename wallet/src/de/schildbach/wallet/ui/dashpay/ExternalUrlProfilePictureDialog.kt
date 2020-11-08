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
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import de.schildbach.wallet.ui.ExternalUrlProfilePictureViewModel
import de.schildbach.wallet.ui.RestoreWalletFromFileViewModel
import de.schildbach.wallet.ui.dashpay.utils.ProfilePictureDisplay
import de.schildbach.wallet.util.KeyboardUtil
import de.schildbach.wallet_test.R
import org.slf4j.LoggerFactory

class ExternalUrlProfilePictureDialog : DialogFragment() {

    companion object {

        private val log = LoggerFactory.getLogger(RestoreWalletFromFileViewModel::class.java)

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
        dialog.window!!.callback = UserInteractionAwareCallback(dialog.window!!.callback, requireActivity())
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

                cleanup()

                if (edit.text.isEmpty()) {
                    positiveButton.isEnabled = true
                    return
                }
                positiveButton.isEnabled = false

                val pictureUrl = edit.text.trim().toString()
                loadUrl(pictureUrl)
                imitateUserInteraction()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        return customView
    }

    private fun cleanup() {
        urlPreview.setImageBitmap(null)
        sharedViewModel.bitmapCache = null
        sharedViewModel.externalUrl = null
    }

    private fun loadUrl(pictureUrlBase: String) {
        val pictureUrl = ProfilePictureDisplay.removePicZoomParameter(convertUrlIfSuitable(pictureUrlBase))
        Glide.with(requireContext())
                .load(pictureUrl)
                .override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>?, isFirstResource: Boolean): Boolean {
                        log.info(e?.localizedMessage ?: "error", e)
                        return false
                    }

                    override fun onResourceReady(resource: Drawable?, model: Any?, target: Target<Drawable>?, dataSource: DataSource?, isFirstResource: Boolean): Boolean {
                        return false
                    }
                })
                .into(object : CustomTarget<Drawable?>() {
                    override fun onResourceReady(@NonNull resource: Drawable, @Nullable transition: Transition<in Drawable?>?) {
                        if (isAdded) {
                            if (resource is BitmapDrawable) {
                                urlPreviewPane.visibility = View.VISIBLE
                                positiveButton.error = null
                                positiveButton.isEnabled = true
                                urlPreview.setImageDrawable(resource)
                                sharedViewModel.bitmapCache = resource.bitmap
                                sharedViewModel.externalUrl = pictureUrl
                            } else {
                                onLoadFailed(null)
                            }
                        }
                    }

                    override fun onLoadCleared(@Nullable placeholder: Drawable?) {

                    }

                    override fun onLoadFailed(@Nullable errorDrawable: Drawable?) {
                        if (isAdded) {
                            urlPreviewPane.visibility = View.GONE
                            positiveButton.isEnabled = false
                            sharedViewModel.bitmapCache = null
                            sharedViewModel.externalUrl = null
                        }
                    }
                })
    }

    private fun convertUrlIfSuitable(pictureUrlBase: String): String {
        // eg. https://drive.google.com/file/d/12rhWM7_wIXwDcFfsANkVGa0ArrbnhrMN/view?usp=sharing
        val googleDrivePreviewPrefix = "https://drive.google.com/file/d/"
        if (pictureUrlBase.startsWith(googleDrivePreviewPrefix)) {
            val pictureUrlBaseUri = Uri.parse(pictureUrlBase)
            if (pictureUrlBaseUri.pathSegments.size == 4) {
                val fileId = pictureUrlBaseUri.pathSegments[2]
                return "https://drive.google.com/uc?export=view&id=$fileId"
            }
        }
        //https://www.dropbox.com/s/2ldd9fjk02yvyv1/IMG_20201103_220114.jpg?dl=0
        val dropboxPreviewPrefix = "https://www.dropbox.com/s/"
        if (pictureUrlBase.startsWith(dropboxPreviewPrefix)) {
            val pictureUrlBaseUri = Uri.parse(pictureUrlBase)
            if (pictureUrlBaseUri.pathSegments.size == 3) {
                val fileId = "${pictureUrlBaseUri.pathSegments[1]}/${pictureUrlBaseUri.pathSegments[2]}"
                return "https://dl.dropboxusercontent.com/s/$fileId"
            }
        }
        return pictureUrlBase;
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        sharedViewModel = activity?.run {
            ViewModelProvider(this)[ExternalUrlProfilePictureViewModel::class.java]
        } ?: throw IllegalStateException("Invalid Activity")
    }

    private fun imitateUserInteraction() {
        requireActivity().onUserInteraction()
    }
}
