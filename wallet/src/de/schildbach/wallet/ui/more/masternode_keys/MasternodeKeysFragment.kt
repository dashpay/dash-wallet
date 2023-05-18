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
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.CheckPinDialog
import de.schildbach.wallet.util.Toast
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentMasternodeKeyTypesBinding
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.ui.viewBinding
import org.slf4j.LoggerFactory
import javax.inject.Inject

@FlowPreview
@AndroidEntryPoint
class MasternodeKeysFragment : Fragment(R.layout.fragment_masternode_key_types) {

    companion object {
        private val log = LoggerFactory.getLogger(MasternodeKeysFragment::class.java)
    }
    private val binding by viewBinding(FragmentMasternodeKeyTypesBinding::bind)

    @Inject
    lateinit var analytics: AnalyticsService
    private val viewModel: MasternodeKeysViewModel by activityViewModels()
    private lateinit var masternodeKeyTypeAdapter: MasternodeKeyTypeAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }
        binding.title.setText(R.string.masternode_keys_title)

        masternodeKeyTypeAdapter = MasternodeKeyTypeAdapter(viewModel.keyChainMap) {
            findNavController().navigate(
                R.id.masternodeKeyChainFragment,
                bundleOf("type" to it),
                NavOptions.Builder()
                    .setEnterAnim(R.anim.slide_in_right)
                    .build(),
            )
        }
        binding.keyTypeList.adapter = masternodeKeyTypeAdapter
        binding.keyTypeList.layoutManager = LinearLayoutManager(activity)
        binding.keyTypeList.setHasFixedSize(true)

        if (viewModel.hasMasternodeKeys()) {
            loadKeyTypes()
        } else {
            CheckPinDialog() { pin ->
                lifecycleScope.launch {
                    viewModel.addKeyChains(pin!!)
                    loadKeyTypes()
                }
            }.showNow(childFragmentManager, "check-pin")
        }
        viewModel.newKeysFound.observe(viewLifecycleOwner) { isAdded ->
            if (isAdded == true) {
                loadKeyTypes()
            }
        }
    }

    private fun loadKeyTypes() {
        masternodeKeyTypeAdapter.notifyDataSetChanged()
    }

    private fun handleCopyAddress(text: String) {
        viewModel.copyToClipboard(text)

        Toast(requireContext()).toast(R.string.copied)
        log.info("text copied to clipboard: {}", text)
    }
}
