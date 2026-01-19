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
import android.view.WindowManager
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import de.schildbach.wallet.ui.DecryptSeedViewModel
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentShowSeedBinding
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.goBack
import org.dash.wallet.common.util.safeNavigate

/**
 * @author Samuel Barbosa
 */
class ShowSeedFragment : Fragment(R.layout.fragment_show_seed) {
    private val binding by viewBinding(FragmentShowSeedBinding::bind)
    private val viewModel: DecryptSeedViewModel by activityViewModels()
    private val args by navArgs<ShowSeedFragmentArgs>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.verifyAppbar.toolbar.title = getString(R.string.view_seed_title)
        binding.verifyAppbar.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        if (args.standalone) {
            viewModel.init(args.seed!!)
        }

        viewModel.seed.observe(viewLifecycleOwner) { seed ->
            val sb = StringBuilder(12)
            seed.forEach {
                sb.append("$it ")
            }
            binding.recoverySeed.text = sb.toString().trim()
        }

        binding.confirmBtn.setOnClickListener {
            if (args.standalone) {
                goBack()
            } else {
                safeNavigate(ShowSeedFragmentDirections.showSeedToConfirm())
            }
        }

        binding.explanationBtn.setOnClickListener {
            OffsetDialogFragment(R.layout.dialog_verify_seed_warning).show(requireActivity())
        }
    }

    override fun onResume() {
        super.onResume()
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onPause() {
        super.onPause()
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
