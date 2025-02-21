/*
 * Copyright 2020 Dash Core Group.
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

package de.schildbach.wallet.ui

import android.graphics.Color
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import de.schildbach.wallet.ui.dashpay.EditProfileViewModel
import de.schildbach.wallet_test.R
import de.schildbach.wallet.ui.dashpay.work.UpdateProfileError
import de.schildbach.wallet_test.databinding.ProfilePictureStateDialogBinding

class UploadProfilePictureStateDialog : DialogFragment() {

    private val showError: UpdateProfileError by lazy {
        UpdateProfileError.getByValue(requireArguments().getInt(ARG_SHOW_ERROR, UpdateProfileError.NO_ERROR.ordinal))!!
    }
    private lateinit var binding: ProfilePictureStateDialogBinding
    private lateinit var editProfileViewModel: EditProfileViewModel

    companion object {

        private const val ARG_SHOW_ERROR = "show_error"

        @JvmStatic
        fun newInstance(showError: UpdateProfileError = UpdateProfileError.NO_ERROR): UploadProfilePictureStateDialog {
            val fragment = UploadProfilePictureStateDialog()
            val args = Bundle()
            args.putInt(ARG_SHOW_ERROR, showError.ordinal)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Translucent_NoTitleBar)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        binding = ProfilePictureStateDialogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        editProfileViewModel = ViewModelProvider(requireActivity())
                .get(EditProfileViewModel::class.java)
        updateUiState(showError)

        val file = editProfileViewModel.profilePictureFile
        if (file != null) {
            Glide.with(requireActivity()).load(file)
                    .circleCrop().signature(ObjectKey(file.lastModified())).into(binding.avatar)

        }

        binding.tryAgainBtn.setOnClickListener {
            dismiss()
            if (showError != UpdateProfileError.AUTHENTICATION) {
                editProfileViewModel.uploadProfilePicture()
            }
        }
        binding.cancelBtn.setOnClickListener {
            if (showError != UpdateProfileError.NO_ERROR) {
                dismiss()
            } else {
                cancelRequest()
            }
        }
    }

    private fun cancelRequest() {
        editProfileViewModel.cancelUploadRequest()
        dismiss()
    }

    override fun onStart() {
        super.onStart()
        val width = ViewGroup.LayoutParams.MATCH_PARENT
        val height = ViewGroup.LayoutParams.MATCH_PARENT
        dialog?.window?.setLayout(width, height)
        dialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    fun showError(error: UpdateProfileError) {
        updateUiState(error)
    }

    private fun updateUiState(error: UpdateProfileError = UpdateProfileError.NO_ERROR) {
        when (error) {
            UpdateProfileError.UPLOAD -> {
                binding.icon.setImageDrawable(ContextCompat.getDrawable(requireContext(), R.drawable.ic_error))
                binding.title.setText(R.string.profile_picture_upload_error_title)
                binding.subtitle.setText(R.string.profile_picture_upload_error_message)
                binding.tryAgainBtn.visibility = View.VISIBLE
                binding.cancelBtn.visibility = View.VISIBLE
            }
            UpdateProfileError.NO_ERROR -> {
                binding.icon.setImageDrawable(ContextCompat.getDrawable(requireContext(), R.drawable.ic_hourglass))
                (binding.icon.drawable as AnimationDrawable).start()
                binding.title.setText(R.string.profile_picture_uploading_title)
                binding.subtitle.setText(R.string.profile_picture_uploading_message)
                binding.tryAgainBtn.visibility = View.GONE
                binding.cancelBtn.visibility = View.VISIBLE
            }
            UpdateProfileError.AUTHENTICATION -> {
                binding.icon.setImageDrawable(ContextCompat.getDrawable(requireContext(), R.drawable.ic_error))
                binding.title.setText(
                        when (editProfileViewModel.storageService) {
                            EditProfileViewModel.ProfilePictureStorageService.GOOGLE_DRIVE -> R.string.select_source_google_drive
                            EditProfileViewModel.ProfilePictureStorageService.IMGUR -> R.string.edit_profile_imgur
                        }
                )
                binding.subtitle.setText(R.string.google_drive_failed_authorization)
                binding.tryAgainBtn.visibility = View.GONE
                binding.cancelBtn.visibility = View.VISIBLE
            }
            else -> {
                // ignore
            }
        }
    }

}