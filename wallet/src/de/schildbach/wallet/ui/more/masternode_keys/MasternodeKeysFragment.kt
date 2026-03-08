/*
 * Copyright 2019 Dash Core Group
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

package de.schildbach.wallet.ui.more.masternode_keys

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet_test.R
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MasternodeKeysFragment : Fragment() {

    private val viewModel: MasternodeKeysViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                MasternodeKeysScreen(
                    uiStateFlow = viewModel.uiState,
                    onBackClick = { findNavController().popBackStack() },
                    onKeyTypeClick = { type ->
                        findNavController().navigate(
                            R.id.masternodeKeyChainFragment,
                            bundleOf("type" to type),
                            NavOptions.Builder()
                                .setEnterAnim(R.anim.slide_in_right)
                                .build()
                        )
                    }
                )
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!viewModel.hasMasternodeKeys()) {
            lifecycleScope.launch {
                viewModel.addKeyChains("")
            }
        }
    }
}