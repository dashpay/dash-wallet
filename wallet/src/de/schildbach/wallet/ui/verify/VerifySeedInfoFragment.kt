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
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentVerifyInfoBinding
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.safeNavigate

class VerifySeedInfoFragment : Fragment(R.layout.fragment_verify_info) {
    private val binding by viewBinding(FragmentVerifyInfoBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.closeButton.setOnClickListener { requireActivity().finish() }
        binding.showRecoveryPhraseButton.setOnClickListener {
            safeNavigate(VerifySeedInfoFragmentDirections.infoToWriteDown())
        }
    }
}
