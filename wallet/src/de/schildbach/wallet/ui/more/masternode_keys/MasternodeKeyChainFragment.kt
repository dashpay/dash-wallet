/*
 * Copyright 2023 Dash Core Group
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
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.util.Toast
import de.schildbach.wallet_test.R
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.slf4j.LoggerFactory
import javax.inject.Inject

@AndroidEntryPoint
class MasternodeKeyChainFragment : Fragment() {

    companion object {
        private val log = LoggerFactory.getLogger(MasternodeKeyChainFragment::class.java)
    }

    @Inject
    lateinit var analytics: AnalyticsService

    private val viewModel: MasternodeKeysViewModel by activityViewModels()
    private val masternodeKeyType by lazy { requireArguments()["type"] as MasternodeKeyType }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                MasternodeKeyChainScreen(
                    uiStateFlow = viewModel.uiState,
                    onBackClick = { findNavController().popBackStack() },
                    onAddKeyClick = {
                        lifecycleScope.launch {
                            viewModel.addKey(masternodeKeyType)
                            viewModel.initKeyChainScreen(masternodeKeyType)
                        }
                    },
                    onCopy = { text ->
                        viewModel.copyToClipboard(text)
                        Toast(requireContext()).toast(R.string.copied)
                        log.info("text copied to clipboard: {}", text)
                    }
                )
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.initKeyChainScreen(masternodeKeyType)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState
                    .map { it.newKeysFound }
                    .distinctUntilChanged()
                    .filter { it }
                    .collect { viewModel.initKeyChainScreen(masternodeKeyType) }
            }
        }
    }
}