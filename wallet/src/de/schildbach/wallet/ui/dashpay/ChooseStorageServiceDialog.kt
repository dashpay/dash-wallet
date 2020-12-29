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
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.ViewModelProvider
import de.schildbach.wallet_test.R
import de.schildbach.wallet.ui.dashpay.EditProfileViewModel.ProfilePictureStorageService
import org.dash.wallet.common.InteractionAwareDialogFragment

class ChooseStorageServiceDialog : InteractionAwareDialogFragment() {

    companion object {

        @JvmStatic
        fun newInstance(): ChooseStorageServiceDialog {
            return ChooseStorageServiceDialog()
        }
    }

    private lateinit var customView: View
    private lateinit var imgurButton: ConstraintLayout
    private lateinit var driveButton: ConstraintLayout
    private lateinit var cancelButton: TextView

    private lateinit var sharedViewModel: SelectProfilePictureSharedViewModel

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.setOnShowListener {
            imgurButton.setOnClickListener {
                sharedViewModel.onChooseStorageService.value = ProfilePictureStorageService.IMGUR
                dismiss()
            }
            driveButton.setOnClickListener {
                sharedViewModel.onChooseStorageService.value = ProfilePictureStorageService.GOOGLE_DRIVE
                dismiss()
            }
            cancelButton.setOnClickListener {
                dismiss()
            }

        }
        return dialog
    }

    @SuppressLint("InflateParams")
    override fun initCustomView(): View {
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
