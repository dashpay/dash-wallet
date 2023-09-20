package org.dash.wallet.integrations.maya.ui

import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
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
import org.dash.wallet.integrations.maya.R
import org.dash.wallet.integrations.maya.databinding.FragmentCurrencyPickerBinding
import org.dash.wallet.integrations.maya.model.PoolInfo

@AndroidEntryPoint
class MayaCryptoCurrencyPickerFragment : Fragment(R.layout.fragment_currency_picker) {
    private val binding by viewBinding(FragmentCurrencyPickerBinding::bind)
    private val viewModel by viewModels<MayaViewModel>()
    private var itemList = listOf<IconifiedViewItem>()
    private lateinit var defaultItemMap: Map<String, IconifiedViewItem>

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        val adapter = IconifiedListAdapter() { item, index ->
            viewModel.poolList.value.firstOrNull {
                it.currencyCode == item.title
            }?.let {
                clickListener(it)
            }
        }

        // val adapter1 = ListAdapter<IconifiedViewItem, RadioButtonViewHolder>(RadioGroupAdapter.DiffCallback())

        val divider = ContextCompat.getDrawable(requireContext(), org.dash.wallet.common.R.drawable.list_divider)!!
        val decorator = ListDividerDecorator(
            divider,
            showAfterLast = false,
            marginStart = resources.getDimensionPixelOffset(org.dash.wallet.common.R.dimen.divider_margin_horizontal),
            marginEnd = resources.getDimensionPixelOffset(org.dash.wallet.common.R.dimen.divider_margin_horizontal)
        )
        binding.contentList.addItemDecoration(decorator)
        binding.contentList.adapter = adapter

        defaultItemMap = mapOf(
            "BTC.BTC" to IconifiedViewItem(
                requireContext().getString(R.string.cryptocurrency_bitcoin_code),
                requireContext().getString(R.string.cryptocurrency_bitcoin_network)
                // R.drawable.ic_btc_logo
            ),
            "ETH.ETH" to IconifiedViewItem(
                requireContext().getString(R.string.cryptocurrency_ethereum_code),
                requireContext().getString(R.string.cryptocurrency_ethereum_network)
                // R.drawable.ic_eth_logo
            ),
            "DASH.DASH" to IconifiedViewItem(
                requireContext().getString(R.string.cryptocurrency_dash_code),
                requireContext().getString(R.string.cryptocurrency_dash_network)
                // R.drawable.ic_dash_d_circle
            ),
            "ETH.USDC-0XA0B86991C6218B36C1D19D4A2E9EB0CE3606EB48" to IconifiedViewItem(
                requireContext().getString(R.string.cryptocurrency_usdcoin_code),
                requireContext().getString(R.string.cryptocurrency_usdcoin_network)
                // R.drawable.blue_circle
            ),
            "ETH.USDT-0XDAC17F958D2EE523A2206206994597C13D831EC7" to IconifiedViewItem(
                requireContext().getString(R.string.cryptocurrency_tether_code),
                requireContext().getString(R.string.cryptocurrency_tether_network)
                // R.drawable.blue_circle
            ),
            "THOR.RUNE" to IconifiedViewItem(
                requireContext().getString(R.string.cryptocurrency_rune_code),
                requireContext().getString(R.string.cryptocurrency_rune_network)
                // R.drawable.ic_dash_d_circle
            )
        )

        viewModel.poolList.observe(viewLifecycleOwner) {
            lifecycleScope.launch {
                itemList = it.filter { pool -> pool.asset != "DASH.DASH" }
                    .map { pool ->
                        if (defaultItemMap.containsKey(pool.asset)) {
                            val item = defaultItemMap[pool.asset]!!.copy(
                                iconUrl = "https://raw.githubusercontent.com/jsupa/crypto-icons/main/icons/" +
                                    "${pool.currencyCode.lowercase()}.png",
                                iconSelectMode = IconSelectMode.None,
                                additionalInfo = GenericUtils.formatFiatWithoutComma(
                                    viewModel.formatFiat(pool.assetPriceFiat)
                                )
                            )
                            println(item.iconUrl + " " + item.iconUrl)
                            item
                        } else {
                            IconifiedViewItem(pool.currencyCode, pool.asset)
                        }
                    }.sortedBy { it.title }
                adapter.submitList(itemList)
            }
        }
    }

    fun clickListener(pool: PoolInfo) {
        AdaptiveDialog.simple("${pool.currencyCode} was chosen", "Close").show(requireActivity()) {
        }
    }
}
