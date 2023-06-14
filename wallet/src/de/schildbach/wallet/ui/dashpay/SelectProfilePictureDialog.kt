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

import android.os.Bundle
import android.view.View
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.DialogSelectPictureBinding
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding

class SelectProfilePictureDialog : OffsetDialogFragment(R.layout.dialog_select_picture) {

    companion object {

        @JvmStatic
        fun createDialog(): DialogFragment {
            return SelectProfilePictureDialog()
        }
    }

    private val sharedViewModel: SelectProfilePictureSharedViewModel by activityViewModels()
    private val binding by viewBinding(DialogSelectPictureBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // take care of actions here
        binding.takePicture.setOnClickListener {
            sharedViewModel.onTakePictureCallback.call()
            dismiss()
        }
        binding.choosePicture.setOnClickListener {
            sharedViewModel.onChoosePictureCallback.call()
            dismiss()
        }
        binding.externalUrl.setOnClickListener {
            sharedViewModel.onFromUrlCallback.call()
            dismiss()
        }
        binding.gravatar.setOnClickListener {
            sharedViewModel.onFromGravatarCallback.call()
            dismiss()
        }
    }
}
