/*
 * Copyright 2022 Dash Core Group.
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

package org.dash.wallet.features.exploredash.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.Constants
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.databinding.FragmentExploreTestnetBinding
import javax.inject.Inject

@AndroidEntryPoint
class ExploreTestNetFragment : Fragment(R.layout.fragment_explore_testnet) {
    private val binding by viewBinding(FragmentExploreTestnetBinding::bind)

    @Inject
    lateinit var walletDataProvider: WalletDataProvider

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.titleBar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        binding.getDashBtn.setOnClickListener {
            val receiveAddress = walletDataProvider.freshReceiveAddress()
            val clipboardManager =
                requireActivity().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

            clipboardManager.setPrimaryClip(ClipData.newPlainText("Dash address", receiveAddress.toString()))

            val faucetIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(Constants.FAUCET_URL)
            )
            startActivity(faucetIntent)
        }
    }
}
