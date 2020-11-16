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
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import de.schildbach.wallet_test.R

class ChooseStorageServiceDialog : DialogFragment() {

    companion object {

        @JvmStatic
        fun newInstance(): ChooseStorageServiceDialog {
            val dialog = ChooseStorageServiceDialog()
            return dialog
        }

        const val sharedPreferencesFile = "upload_service_disclaimer"
        const val showFullDisclaimer = "upload_service_show_full_disclaimer"
    }

    private lateinit var customView: View
    private lateinit var imgurButton: ConstraintLayout
    private lateinit var driveButton: ConstraintLayout
    private lateinit var cancelButton: TextView
    private lateinit var disclaimer: TextView
    private lateinit var fullDisclaimer: ConstraintLayout

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
        disclaimer = customView.findViewById(R.id.external_storage_disclaimer)
        fullDisclaimer = customView.findViewById(R.id.external_storage_full_disclaimer)


        val prefs = requireActivity().getSharedPreferences(sharedPreferencesFile, Context.MODE_PRIVATE)
        if (prefs.getBoolean(showFullDisclaimer, true)) {
            disclaimer.isVisible = false
            fullDisclaimer.isVisible = true
            prefs.edit().putBoolean(showFullDisclaimer, false).apply()
        } else {
            disclaimer.isVisible = true
            fullDisclaimer.isVisible = false
        }
        return customView
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        sharedViewModel = activity?.run {
            ViewModelProvider(this)[SelectProfilePictureSharedViewModel::class.java]
        } ?: throw IllegalStateException("Invalid Activity")
    }
}
