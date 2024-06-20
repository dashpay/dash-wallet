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

package org.dash.wallet.integrations.maya.ui

import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.dash.wallet.common.ui.decorators.ListDividerDecorator
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.radio_group.IconSelectMode
import org.dash.wallet.common.ui.radio_group.IconifiedViewItem
import org.dash.wallet.common.ui.recyclerview.IconifiedListAdapter
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.observe
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.integrations.maya.R
import org.dash.wallet.integrations.maya.databinding.FragmentCurrencyPickerBinding
import org.dash.wallet.integrations.maya.model.PoolInfo
import org.dash.wallet.integrations.maya.payments.MayaCurrencyList
import org.slf4j.LoggerFactory

@AndroidEntryPoint
class MayaCryptoCurrencyPickerFragment : Fragment(R.layout.fragment_currency_picker) {
    companion object {
        private val log = LoggerFactory.getLogger(MayaCryptoCurrencyPickerFragment::class.java)
    }
    private val binding by viewBinding(FragmentCurrencyPickerBinding::bind)
    private val viewModel by mayaViewModels<MayaViewModel>()
    private var itemList = listOf<IconifiedViewItem>()
    private lateinit var defaultItemMap: Map<String, IconifiedViewItem>

    class FullDiffCallback : DiffUtil.ItemCallback<IconifiedViewItem>() {
        override fun areItemsTheSame(oldItem: IconifiedViewItem, newItem: IconifiedViewItem): Boolean {
            return oldItem.title == newItem.title &&
                    oldItem.iconRes == newItem.iconRes &&
                    oldItem.additionalInfo == newItem.additionalInfo
        }

        override fun areContentsTheSame(oldItem: IconifiedViewItem, newItem: IconifiedViewItem): Boolean {
            return oldItem == newItem
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        val adapter = IconifiedListAdapter(diffCallback = FullDiffCallback()) { item, _ ->
            viewModel.poolList.value.firstOrNull {
                it.asset == item.id
            }?.let {
                val inboundAddress = viewModel.getInboundAddress(it.asset)
                if (inboundAddress != null && !inboundAddress.halted) {
                    clickListener(it)
                } else {
                    AdaptiveDialog.create(
                        null,
                        getString(R.string.error),
                        getString(R.string.maya_error_trading_halted, it.asset),
                        getString(R.string.button_close)
                    ).show(requireActivity())
                }
            }
        }

        val divider = ContextCompat.getDrawable(requireContext(), org.dash.wallet.common.R.drawable.list_divider)!!
        val decorator = ListDividerDecorator(
            divider,
            showAfterLast = false,
            marginStart = resources.getDimensionPixelOffset(org.dash.wallet.common.R.dimen.divider_margin_horizontal),
            marginEnd = resources.getDimensionPixelOffset(org.dash.wallet.common.R.dimen.divider_margin_horizontal)
        )
        binding.contentList.addItemDecoration(decorator)
        binding.contentList.adapter = adapter

        // using this allows for translation of cryptocurrency names
        defaultItemMap = MayaCurrencyList.all.associateBy({ it.asset }, {
            IconifiedViewItem(
                requireContext().getString(it.codeId),
                requireContext().getString(it.nameId)
            )
        })

        binding.searchQuery.doAfterTextChanged { text ->
            lifecycleScope.launch {
                if (!text.isNullOrEmpty()) {
                    val fromQuery = itemList.filter {
                        it.title.contains(text.toString().uppercase()) || it.subtitle.uppercase()
                            .contains(text.toString().uppercase())
                    }
                    adapter.submitList(fromQuery)
                } else {
                    adapter.submitList(itemList)
                }
            }
        }

        viewModel.poolList.observe(viewLifecycleOwner) {
            lifecycleScope.launch {
                itemList = it.filter { pool -> pool.asset != "DASH.DASH" }
                    .filter { pool -> defaultItemMap.containsKey(pool.asset) && pool.status == "available" }
                    .map { pool ->
                        if (defaultItemMap.containsKey(pool.asset)) {
                            defaultItemMap[pool.asset]!!.copy(
                                iconUrl = GenericUtils.getCoinIcon(pool.currencyCode),
                                iconSelectMode = IconSelectMode.None,
                                additionalInfo = GenericUtils.formatFiatWithoutComma(
                                    viewModel.formatFiat(pool.assetPriceFiat)
                                ),
                                id = pool.asset
                            )
                        } else {
                            IconifiedViewItem(
                                pool.currencyCode,
                                pool.asset,
                                iconUrl = GenericUtils.getCoinIcon(pool.currencyCode),
                                iconSelectMode = IconSelectMode.None,
                                additionalInfo = GenericUtils.formatFiatWithoutComma(
                                    viewModel.formatFiat(pool.assetPriceFiat)
                                ),
                                id = pool.asset
                            )
                        }
                    }.sortedBy { it.title }
                log.info("exchange rate: updating itemList with {}", itemList[0].additionalInfo)
                adapter.submitList(itemList)
            }
        }

        viewModel.uiState.observe(viewLifecycleOwner) { uiState ->
            uiState.errorCode?.let {
                showErrorAlert(it)
            }
        }
    }

    private fun showErrorAlert(code: Int) {
        var messageId = R.string.loading_error

        if (code == 400 || code == 408 || code >= 500) messageId = R.string.maya_error_not_available
        if (code == 403 || code >= 400) messageId = R.string.maya_error_report_issue

        AdaptiveDialog.create(
            R.drawable.ic_error,
            getString(R.string.maya_error),
            getString(messageId),
            getString(android.R.string.ok)
        ).show(requireActivity()) {
            viewModel.errorHandled()
        }
    }

    private fun clickListener(pool: PoolInfo) {
        safeNavigate(
            MayaCryptoCurrencyPickerFragmentDirections.mayaCurrencyPickerToAddressInput(
                pool.currencyCode,
                pool.asset,
                getString(R.string.maya_address_input_title, pool.currencyCode),
                getString(R.string.maya_address_input_hint, pool.currencyCode)
            )
        )

        // AdaptiveDialog.simple("${pool.currencyCode} was chosen", "Close").show(requireActivity()) {
        // }
    }
}
