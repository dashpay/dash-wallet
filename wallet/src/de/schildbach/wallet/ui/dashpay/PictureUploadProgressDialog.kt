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
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import de.schildbach.wallet.ui.dashpay.utils.ProfilePictureDisplay
import de.schildbach.wallet_test.R
import org.slf4j.LoggerFactory
import com.google.api.services.drive.Drive
import de.schildbach.wallet.livedata.Status

class PictureUploadProgressDialog(val drive: Drive?) : DialogFragment() {

    companion object {

        private val log = LoggerFactory.getLogger(PictureUploadProgressDialog::class.java)

        @JvmStatic
        fun newInstance(drive: Drive? = null): PictureUploadProgressDialog {
            val dialog = PictureUploadProgressDialog(drive)
            return dialog
        }
    }

    private lateinit var customView: View
    private lateinit var errorIcon: ImageView
    private lateinit var message: TextView
    private lateinit var title: TextView
    private lateinit var retryButton: Button
    private lateinit var cancelButton: Button
    private lateinit var pendingWorkIcon: ImageView
    private lateinit var avatar: ImageView

    private lateinit var sharedViewModel: EditProfileViewModel

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialogBuilder = AlertDialog.Builder(requireContext())
                .setView(initCustomView())

        val dialog = dialogBuilder.create()
        dialog.setOnShowListener {

        }
        dialog.window!!.callback = UserInteractionAwareCallback(dialog.window!!.callback, requireActivity())
        return dialog
    }

    private fun startUpload() {
        // start the upload process
        when (sharedViewModel.uploadService) {
            EditProfileViewModel.GoogleDrive -> {
                sharedViewModel.uploadToGoogleDrive(drive!!)
            }
            EditProfileViewModel.Imgur -> {
                sharedViewModel.uploadToImgUr()
            }
        }

        message.text = getString(R.string.upload_image_message)
        title.text = getString(R.string.upload_image_please_wait)
        errorIcon.isVisible = false
        pendingWorkIcon.isVisible = true
        (pendingWorkIcon.drawable as AnimationDrawable).start()
    }

    @SuppressLint("SetTextI18n")
    private fun initCustomView(): View {
        customView = requireActivity().layoutInflater.inflate(R.layout.dialog_image_upload, null)
        message = customView.findViewById(R.id.upload_image_message)
        title = customView.findViewById(R.id.please_wait)
        retryButton = customView.findViewById(R.id.error_try_again)
        cancelButton = customView.findViewById(R.id.cancel)
        pendingWorkIcon = customView.findViewById(R.id.pending_work_icon)
        errorIcon = customView.findViewById(R.id.error_icon)
        avatar = customView.findViewById(R.id.avatar)
        retryButton.setOnClickListener {
            startUpload()
            imitateUserInteraction()
        }
        cancelButton.setOnClickListener {
            //TODO: this does not yet cancel the operation
            imitateUserInteraction()
            dismiss()
        }
        errorIcon.isVisible = false
        pendingWorkIcon.isVisible = false
        cancelButton.isVisible = true
        retryButton.isVisible = false
        return customView
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        sharedViewModel = activity?.run {
            ViewModelProvider(this)[EditProfileViewModel::class.java]
        } ?: throw IllegalStateException("Invalid Activity")

        ProfilePictureDisplay.display(avatar, sharedViewModel.profilePictureUri,
                sharedViewModel.profilePictureFile!!.lastModified(),
                sharedViewModel.dashPayProfile!!.username)

        sharedViewModel.profilePictureUploadLiveData.observe(this, {
            if (it != null) {
                when(it.status) {
                    Status.LOADING -> {

                    }
                    Status.ERROR -> {
                        log.error("picture upload failed: ${sharedViewModel.uploadService} ${it.exception} ${it.message}")
                        errorIcon.isVisible = true
                        pendingWorkIcon.isVisible = false
                        message.text = getString(R.string.upload_image_error_message)
                        title.text = getString(R.string.upload_image_upload_error)
                        cancelButton.isVisible = false
                        retryButton.isVisible = true
                    }
                    Status.SUCCESS -> {
                        dismiss()
                    }
                    Status.CANCELED -> {

                    }
                }
            }
            imitateUserInteraction()
        })

        startUpload()
    }

    private fun imitateUserInteraction() {
        requireActivity().onUserInteraction()
    }
}
