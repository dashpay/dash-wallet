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
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.ConstraintLayout
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
import de.schildbach.wallet.util.KeyboardUtil
import de.schildbach.wallet_test.R
import org.slf4j.LoggerFactory


class ChooseStorageServiceDialog : DialogFragment() {

    companion object {

        private val log = LoggerFactory.getLogger(ChooseStorageServiceDialog::class.java)

        private const val ARG_INITIAL_URL = "arg_initial_url"

        @JvmStatic
        fun newInstance(): ChooseStorageServiceDialog {
            val dialog = ChooseStorageServiceDialog()
            return dialog
        }
    }

    private val initialUrl by lazy {
        arguments?.getString(ARG_INITIAL_URL)
    }

    private lateinit var customView: View
    private lateinit var imgurButton: ConstraintLayout
    private lateinit var driveButton: ConstraintLayout
    private lateinit var cancelButton: TextView

    private lateinit var sharedViewModel: SelectProfilePictureSharedViewModel

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialogBuilder = AlertDialog.Builder(requireContext())
                .setView(initCustomView())

        val dialog = dialogBuilder.create()
        dialog.setOnShowListener {
            imgurButton.setOnClickListener {
                sharedViewModel.onChooseStorageService.value = EditProfileViewModel.Imgur
                dismiss()
            }
            driveButton.setOnClickListener {
                sharedViewModel.onChooseStorageService.value = EditProfileViewModel.GoogleDrive
                dismiss()
            }
            cancelButton.setOnClickListener {
                dismiss()
            }

        }
        return dialog
    }

    @SuppressLint("InflateParams")
    private fun initCustomView(): View {
        customView = requireActivity().layoutInflater.inflate(R.layout.dialog_select_storage_service, null)
        imgurButton = customView.findViewById(R.id.imgur)
        driveButton = customView.findViewById(R.id.google_drive)
        cancelButton = customView.findViewById(R.id.cancel)
        return customView
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        sharedViewModel = activity?.run {
            ViewModelProvider(this)[SelectProfilePictureSharedViewModel::class.java]
        } ?: throw IllegalStateException("Invalid Activity")
    }
}
