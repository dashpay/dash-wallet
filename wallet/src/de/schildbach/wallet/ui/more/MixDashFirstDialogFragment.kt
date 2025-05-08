/*
 * Copyright (c) 2024 Dash Core Group
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui.more

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.CreateUsernameActivity
import de.schildbach.wallet.ui.coinjoin.CoinJoinActivity
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.DialogMixDashFirstBinding
import kotlinx.coroutines.launch
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding

@AndroidEntryPoint
class MixDashFirstDialogFragment : OffsetDialogFragment(R.layout.dialog_mix_dash_first) {
    private val binding by viewBinding(DialogMixDashFirstBinding::bind)
    val viewModel by viewModels<MixDashFirstViewModel>()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private var onConfirm: (() -> Unit)? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.skipButton.setOnClickListener {
            startActivity(Intent(requireContext(), CreateUsernameActivity::class.java))
            viewModel.setMixDashShown()
            dismiss()
        }

        binding.mixButton.setOnClickListener {
            lifecycleScope.launch {
                val shouldShowFirstTimeInfo = settingsViewModel.shouldShowCoinJoinInfo()

                if (shouldShowFirstTimeInfo) {
                    settingsViewModel.setCoinJoinInfoShown()
                }

                val intent = Intent(requireActivity(), CoinJoinActivity::class.java)
                intent.putExtra(CoinJoinActivity.FIRST_TIME_EXTRA, shouldShowFirstTimeInfo)
                startActivity(intent)
                viewModel.setMixDashShown()
                dismiss()
            }
        }
    }

    fun show(fragmentActivity: FragmentActivity, onConfirm: () -> Unit) {
        this.onConfirm = onConfirm
        show(fragmentActivity)
    }
}
