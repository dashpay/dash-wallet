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
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.util.Toast
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentMasternodeKeyChainBinding
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.bitcoinj.crypto.IKey
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.ui.viewBinding
import org.slf4j.LoggerFactory
import javax.inject.Inject

@FlowPreview
@AndroidEntryPoint
class MasternodeKeyChainFragment : Fragment(R.layout.fragment_masternode_key_chain) {

    companion object {
        private val log = LoggerFactory.getLogger(MasternodeKeyChainFragment::class.java)
    }
    private val binding by viewBinding(FragmentMasternodeKeyChainBinding::bind)

    @Inject
    lateinit var analytics: AnalyticsService
    private val viewModel: MasternodeKeysViewModel by activityViewModels()
    private lateinit var masternodeKeyChainAdapter: MasternodeKeyChainAdapter
    private val masternodeKeyType by lazy { requireArguments()["type"] as MasternodeKeyType }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }
        binding.title.setText(
            when (masternodeKeyType) {
                MasternodeKeyType.OWNER -> R.string.masternode_key_type_owner
                MasternodeKeyType.VOTING -> R.string.masternode_key_type_voting
                MasternodeKeyType.OPERATOR -> R.string.masternode_key_type_operator
                MasternodeKeyType.PLATFORM -> R.string.masternode_key_type_platform
                else -> throw IllegalArgumentException("invalid masternode key type")
            }
        )
        masternodeKeyChainAdapter = MasternodeKeyChainAdapter(
            viewModel.getKeyChainInfo(masternodeKeyType, false),
            viewModel.getKeyUsage(),
            { handleCopyAddress(it) },
            { key, position -> handleDecryptKey(key, position) }
        )
        binding.keyList.adapter = masternodeKeyChainAdapter
        binding.keyList.layoutManager = LinearLayoutManager(activity)
        binding.keyList.setHasFixedSize(true)
        masternodeKeyChainAdapter.notifyDataSetChanged()

        binding.addMasternodeKey.setOnClickListener {
            lifecycleScope.launch {
                val position = viewModel.addKey(masternodeKeyType)
                masternodeKeyChainAdapter.addKey(position)
                (binding.keyList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(
                    position,
                    0
                )
            }
        }
        viewModel.newKeysFound.observe(viewLifecycleOwner) { isAdded ->
            if (isAdded == true) {
                masternodeKeyChainAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun handleCopyAddress(text: String) {
        viewModel.copyToClipboard(text)

        Toast(requireContext()).toast(R.string.copied)
        log.info("text copied to clipboard: {}", text)
    }

    private fun handleDecryptKey(key: IKey, position: Int) {
        lifecycleScope.launch {
            val mninfo = viewModel.getDecryptedKey(key)
            masternodeKeyChainAdapter.keyChainInfo.masternodeKeyInfoList[position] = mninfo
            if (binding.keyList.isComputingLayout) {
                delay(500)
                handleDecryptKey(key, position)
            } else {
                masternodeKeyChainAdapter.notifyItemChanged(position)
            }
        }
    }
}
