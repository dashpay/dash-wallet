package de.schildbach.wallet.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import com.bumptech.glide.Glide
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.ui.dashpay.EditProfileViewModel
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.imgur_policy_dialog.*
import kotlinx.android.synthetic.main.profile_picture_state_dialog.*
import kotlinx.android.synthetic.main.profile_picture_state_dialog.cancel_btn

class DeleteProfilePictureConfirmationDialog : DialogFragment() {

    private lateinit var editProfileViewModel: EditProfileViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Translucent_NoTitleBar)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.delete_image_confirmation_dialog, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val editProfileViewModel = ViewModelProvider(requireActivity()).get(EditProfileViewModel::class.java)
        val avatarUrl = editProfileViewModel.dashPayProfile?.avatarUrl
        Glide.with(view).load(avatarUrl).circleCrop().into(avatar)
        agree_btn.setOnClickListener {
            dismiss()
            editProfileViewModel.deleteProfilePictureConfirmationLiveData.postValue(true)
        }
        cancel_btn.setOnClickListener {
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        val width = ViewGroup.LayoutParams.MATCH_PARENT
        val height = ViewGroup.LayoutParams.MATCH_PARENT
        dialog?.window?.setLayout(width, height)
        dialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

}