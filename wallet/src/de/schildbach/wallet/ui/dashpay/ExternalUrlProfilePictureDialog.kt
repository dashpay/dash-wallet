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
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.*
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.lifecycle.ViewModelProvider
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import com.bumptech.glide.signature.ObjectKey
import de.schildbach.wallet.ui.ExternalUrlProfilePictureViewModel
import org.dash.wallet.common.ui.avatar.ProfilePictureHelper
import org.dash.wallet.common.ui.avatar.ProfilePictureHelper.OnResourceReadyListener
import de.schildbach.wallet_test.R
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.InteractionAwareDialogFragment
import org.dash.wallet.common.util.KeyboardUtil
import org.slf4j.LoggerFactory
import java.math.BigInteger
import java.util.regex.Matcher
import java.util.regex.Pattern

open class ExternalUrlProfilePictureDialog : InteractionAwareDialogFragment() {

    companion object {

        private val log = LoggerFactory.getLogger(ExternalUrlProfilePictureDialog::class.java)
        private val VALID_URL_REGEX: Pattern = Pattern.compile("\\b(https?)://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]")

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
    private lateinit var dialogTitle: TextView
    private lateinit var dialogIcon: ImageView
    private lateinit var dialogPrompt: TextView
    private lateinit var urlPreviewPane: View
    private lateinit var urlPreview: ImageView
    private lateinit var publicUrlEnterUrl: TextView
    private lateinit var button_ok: Button
    private lateinit var button_cancel: Button
    private lateinit var button_cancel_two: Button
    private lateinit var pendingWorkIcon: ImageView
    private lateinit var viewSwitcher: ViewSwitcher
    private lateinit var disclaimer: TextView
    private lateinit var fetchingMessage: TextView

    protected open val errorMessageId = R.string.public_url_error_message
    protected open val fetchingMessageId = R.string.public_url_fetching_image
    protected open val disclaimerMessageId = R.string.public_url_message
    protected open val dialogTitleId = R.string.edit_profile_public_url
    protected open val dialogIconId = R.drawable.ic_external_url
    protected open val dialogPromptId = R.string.public_url_enter_url

    private lateinit var sharedViewModel: ExternalUrlProfilePictureViewModel

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.setOnShowListener {
            if (initialUrl != null) {
                edit.setText(initialUrl)
            }
        }
        return dialog
    }

    @SuppressLint("SetTextI18n")
    override fun initCustomView(): View {
        customView = requireActivity().layoutInflater.inflate(R.layout.dialog_input_text, null)
        dialogPrompt = customView.findViewById(R.id.public_url_enter_url)
        dialogTitle = customView.findViewById(R.id.public_url_title)
        dialogIcon = customView.findViewById(R.id.public_url_icon)
        edit = customView.findViewById(R.id.input)
        urlPreviewPane = customView.findViewById(R.id.url_preview_pane)
        urlPreview = customView.findViewById(R.id.url_preview)
        publicUrlEnterUrl = customView.findViewById(R.id.public_url_enter_url)
        button_ok = customView.findViewById(R.id.ok)
        button_cancel = customView.findViewById(R.id.cancel)
        button_cancel_two = customView.findViewById(R.id.cancel_fetching)
        pendingWorkIcon = customView.findViewById(R.id.pending_work_icon)
        urlPreviewPane.visibility = View.GONE
        viewSwitcher = customView.findViewById(R.id.view_switcher)
        disclaimer = customView.findViewById(R.id.public_url_message)
        fetchingMessage = customView.findViewById(R.id.fetching_msg)
        disclaimer.apply {
            text = HtmlCompat.fromHtml(
                    getString(R.string.public_url_message) +
                            " <html><a href=\"https://www.google.com/amp/s/www.mail-signatures.com/articles/direct-link-to-hosted-image/amp/\"><span style=\"color:blue;\">${getString(R.string.public_url_more_info)}</span></a></html>",
                    HtmlCompat.FROM_HTML_MODE_COMPACT
            )
            movementMethod = LinkMovementMethod.getInstance()
        }
        edit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {

                cleanup()
                imitateUserInteraction()

                if (edit.text.isEmpty()) {

                    button_ok.isEnabled = false
                    return
                }

                button_ok.isEnabled = true
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        button_ok.setOnClickListener {
            imitateUserInteraction()
            if (!isTextValid(edit.text.trim().toString())) {
                showError()
            } else {
                KeyboardUtil.hideKeyboard(requireContext(), edit)
                cleanup()

                button_ok.isEnabled = false

                val pictureUrl = edit.text.trim().toString()
                (pendingWorkIcon.drawable as AnimationDrawable).start()

                viewSwitcher.showNext()

                loadFromString(pictureUrl)
            }
        }
        button_cancel.setOnClickListener {
            KeyboardUtil.hideKeyboard(requireContext(), edit)
            dismiss()
        }
        button_cancel_two.setOnClickListener {
            //TODO: how do we cancel an image load operation that is taking forever?
            viewSwitcher.showPrevious()
            button_ok.isEnabled = true
        }
        fetchingMessage.text = getString(fetchingMessageId)
        disclaimer.text = getString(disclaimerMessageId)
        dialogIcon.setImageResource(dialogIconId)
        dialogTitle.text = getString(dialogTitleId)
        dialogPrompt.text = getString(dialogPromptId)

        return customView
    }

    private fun cleanup() {
        urlPreview.setImageBitmap(null)
        if (this::sharedViewModel.isInitialized) {
            sharedViewModel.bitmapCache = null
            sharedViewModel.externalUrl = null
        }
    }

    protected open fun isTextValid(text: String): Boolean {
        if (text.length > 256) {
            return false
        }

        val matcher: Matcher = VALID_URL_REGEX.matcher(text)
        return matcher.find()
    }

    protected open fun loadFromString(text: String) {
        loadUrl(text)
    }

    protected fun loadUrl(pictureUrlBase: String) {
        val pictureUrl = ProfilePictureHelper.removePicZoomParameter(convertUrlIfSuitable(pictureUrlBase))
        Glide.with(requireContext())
                .load(pictureUrl)
                .signature(ObjectKey(System.currentTimeMillis()))
                .override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>?, isFirstResource: Boolean): Boolean {
                        publicUrlEnterUrl.setText(errorMessageId)
                        publicUrlEnterUrl.setTextColor(ContextCompat.getColor(requireContext(), R.color.dash_red))
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
                                ProfilePictureHelper.avatarHashAndFingerprint(requireContext(), pictureUrl, null, object : OnResourceReadyListener {
                                    override fun onResourceReady(avatarHash: Sha256Hash?, avatarFingerprint: BigInteger?) {
                                        if (isAdded) {
                                            sharedViewModel.avatarHash = avatarHash
                                            sharedViewModel.avatarFingerprint = avatarFingerprint

                                            sharedViewModel.bitmapCache = resource.bitmap
                                            sharedViewModel.externalUrl = pictureUrl
                                            publicUrlEnterUrl.text = getString(dialogPromptId)
                                            publicUrlEnterUrl.setTextColor(ContextCompat.getColor(requireContext(), R.color.dash_medium_gray))
                                publicUrlEnterUrl.setTextColor(ContextCompat.getColor(requireContext(), R.color.dash_medium_gray))
                                            sharedViewModel.confirm()
                                            dismiss()
                                        }
                                    }
                                })
                            } else {
                                onLoadFailed(null)
                            }
                        }
                    }

                    override fun onLoadCleared(@Nullable placeholder: Drawable?) {

                    }

                    override fun onLoadFailed(@Nullable errorDrawable: Drawable?) {
                        if (isAdded) {
                            viewSwitcher.showPrevious()
                            sharedViewModel.bitmapCache = null
                            sharedViewModel.externalUrl = null
                        }
                    }
                })
    }

    private fun showError() {
        publicUrlEnterUrl.text = getString(errorMessageId)
        publicUrlEnterUrl.setTextColor(ContextCompat.getColor(requireContext(), R.color.dash_red))
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

    protected fun setEditHint(stringId: Int) {
        edit.hint = getString(stringId)
    }

    protected fun setEditSingleLine(isSingleLine: Boolean) {
        edit.isSingleLine = isSingleLine
    }
}
