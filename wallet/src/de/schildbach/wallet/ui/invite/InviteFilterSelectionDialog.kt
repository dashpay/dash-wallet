/*
 * Copyright 2021 Dash Core Group
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

package de.schildbach.wallet.ui.invite

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModelStoreOwner
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.BaseBottomSheetDialogFragment
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.DialogInviteFilterBinding

@AndroidEntryPoint
class InviteFilterSelectionDialog(private val owner: ViewModelStoreOwner) :
    BaseBottomSheetDialogFragment() {

    companion object {

        @JvmStatic
        fun createDialog(owner: ViewModelStoreOwner): DialogFragment {
            return InviteFilterSelectionDialog(owner)
        }
    }

    private var _binding: DialogInviteFilterBinding? = null
    private val binding get() = _binding!!

    private val sharedViewModel: InvitesHistoryFilterViewModel by viewModels({ owner })

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogInviteFilterBinding.inflate(inflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val array = view.context.resources.getStringArray(R.array.invite_filter)
        binding.firstItem.text = array[0]
        binding.secondItem.text = array[1]
        binding.thirdItem.text = array[2]

        // take care of actions here
        binding.first.setOnClickListener {
            sharedViewModel.setFilter(InvitesHistoryViewModel.Filter.ALL)
            dismiss()
        }
        binding.second.setOnClickListener {
            sharedViewModel.setFilter(InvitesHistoryViewModel.Filter.PENDING)
            dismiss()
        }
        binding.third.setOnClickListener {
            sharedViewModel.setFilter(InvitesHistoryViewModel.Filter.CLAIMED)
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
