/*
 * Copyright 2023 Dash Core Group.
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

package de.schildbach.wallet.ui.verify

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import de.schildbach.wallet.ui.DecryptSeedViewModel
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentVerifyWriteDownBinding
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.safeNavigate

/**
 * @author Samuel Barbosa
 */
class VerifySeedWriteDownFragment : Fragment(R.layout.fragment_verify_write_down) {
    private val binding by viewBinding(FragmentVerifyWriteDownBinding::bind)
    private val viewModel: DecryptSeedViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.verifyAppbar.toolbar.title = getString(R.string.view_seed_title)
        binding.verifyAppbar.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        val seed = viewModel.seed.value ?: throw IllegalStateException("Recovery seed is empty")

        val sb = StringBuilder(12)
        seed.forEach {
            sb.append("$it ")
        }
        binding.recoverySeed.text = sb.toString().trim()

        binding.confirmBtn.setOnClickListener {
            safeNavigate(VerifySeedWriteDownFragmentDirections.writeDownToConfirm())
        }

        binding.explanationBtn.setOnClickListener {
            VerifySeedWarningDialog().show(parentFragmentManager, "verify_seed_warning")
        }
    }
}
