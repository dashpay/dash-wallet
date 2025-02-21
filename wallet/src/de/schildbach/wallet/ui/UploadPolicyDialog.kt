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

package de.schildbach.wallet.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import de.schildbach.wallet.ui.dashpay.EditProfileViewModel
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.UploadPolicyDialogBinding

class UploadPolicyDialog : DialogFragment() {

    private lateinit var binding: UploadPolicyDialogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Translucent_NoTitleBar)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        binding = UploadPolicyDialogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val editProfileViewModel = ViewModelProvider(requireActivity()).get(EditProfileViewModel::class.java)
        if (editProfileViewModel.storageService == EditProfileViewModel.ProfilePictureStorageService.GOOGLE_DRIVE) {
            binding.policyTextOne.setText(R.string.google_drive_policy_one)
            binding.policyTextTwo.setText(R.string.google_drive_policy_two)
            binding.policyTextThree.setText(R.string.google_drive_policy_three)
        }
        binding.agreeBtn.setOnClickListener {
            dismiss()
            editProfileViewModel.uploadDialogAcceptLiveData.postValue(true)
        }
        binding.cancelBtn.setOnClickListener {
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