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
import de.schildbach.wallet.ui.dashpay.EditProfileViewModel
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.profile_picture_state_dialog.*

class UploadProfilePictureStateDialog : DialogFragment() {

    private val showError by lazy { requireArguments().getBoolean(ARG_SHOW_ERROR, false) }
    private lateinit var editProfileViewModel: EditProfileViewModel

    companion object {

        private const val ARG_SHOW_ERROR = "show_error"

        @JvmStatic
        fun newInstance(showError: Boolean = false): UploadProfilePictureStateDialog {
            val fragment = UploadProfilePictureStateDialog()
            val args = Bundle()
            args.putBoolean(ARG_SHOW_ERROR, showError)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Translucent_NoTitleBar)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.profile_picture_state_dialog, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        updateUiState(showError)
        editProfileViewModel = ViewModelProvider(requireActivity())
                .get(EditProfileViewModel::class.java)
        Glide.with(requireActivity()).load(editProfileViewModel.profilePictureFile)
                .circleCrop().into(avatar)
        try_again_btn.setOnClickListener {
            dismiss()
            editProfileViewModel.uploadProfilePicture()
        }
        cancel_btn.setOnClickListener {
            if (showError) {
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

    fun showError() {
        updateUiState(true)
    }

    private fun updateUiState(showError: Boolean = false) {
        if (showError) {
            icon.setImageDrawable(ContextCompat.getDrawable(requireContext(), R.drawable.ic_error))
            title.setText(R.string.profile_picture_upload_error_title)
            subtitle.setText(R.string.profile_picture_upload_error_message)
            try_again_btn.visibility = View.VISIBLE
            cancel_btn.visibility = View.VISIBLE
        } else {
            icon.setImageDrawable(ContextCompat.getDrawable(requireContext(), R.drawable.ic_hourglass))
            (icon.drawable as AnimationDrawable).start()
            title.setText(R.string.profile_picture_uploading_title)
            subtitle.setText(R.string.profile_picture_uploading_message)
            try_again_btn.visibility = View.GONE
            cancel_btn.visibility = View.VISIBLE
        }
    }

}