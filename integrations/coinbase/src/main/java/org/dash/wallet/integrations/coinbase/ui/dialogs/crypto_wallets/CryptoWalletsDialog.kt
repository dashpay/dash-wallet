/*
 * Copyright 2021 Dash Core Group.
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
package org.dash.wallet.integrations.coinbase.ui.dialogs.crypto_wallets

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.R
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.databinding.DialogOptionPickerBinding
import org.dash.wallet.common.ui.decorators.ListDividerDecorator
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.radio_group.IconSelectMode
import org.dash.wallet.common.ui.radio_group.IconifiedViewItem
import org.dash.wallet.common.ui.radio_group.RadioGroupAdapter
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.integrations.coinbase.model.CoinBaseUserAccountDataUIModel
import org.dash.wallet.integrations.coinbase.model.getCoinBaseExchangeRateConversion

@AndroidEntryPoint
class CryptoWalletsDialog(
    private val selectedCurrencyCode: String = "USD",
    private val clickListener: (Int, DialogFragment) -> Unit
) : OffsetDialogFragment(R.layout.dialog_option_picker) {
    override val forceExpand: Boolean = true
    private val binding by viewBinding(DialogOptionPickerBinding::bind)
    private val viewModel: CryptoWalletsDialogViewModel by viewModels()
    private var itemList = listOf<IconifiedViewItem>()
    private var didFocusOnSelected = false
    private var adapter: RadioGroupAdapter? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.progressRing.isVisible = true
        binding.searchTitle.text = getString(R.string.select_a_coin)

        this.adapter = RadioGroupAdapter(0) { item, _ ->
            val index = itemList.indexOfFirst { it.title == item.title }
            clickListener.invoke(index, this)
        }
        val divider = ContextCompat.getDrawable(requireContext(), R.drawable.list_divider)!!
        val decorator = ListDividerDecorator(
            divider,
            showAfterLast = false,
            marginStart = resources.getDimensionPixelOffset(R.dimen.divider_margin_horizontal),
            marginEnd = resources.getDimensionPixelOffset(R.dimen.divider_margin_horizontal)
        )
        binding.contentList.addItemDecoration(decorator)
        binding.contentList.adapter = adapter

        binding.searchQuery.doOnTextChanged { text, _, _, _ ->
            binding.clearBtn.isVisible = !text.isNullOrEmpty()

            adapter?.submitList(
                if (text.isNullOrBlank()) {
                    itemList
                } else {
                    filterByQuery(itemList, text.toString())
                }
            )
        }

        binding.searchQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val inputManager = requireContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                inputManager.hideSoftInputFromWindow(binding.searchQuery.windowToken, 0)
            }

            true
        }

        binding.clearBtn.setOnClickListener {
            binding.searchQuery.text.clear()
        }

        viewModel.exchangeRate.observe(viewLifecycleOwner) { rate ->
            refreshItems(rate, viewModel.dataList.value ?: listOf())
        }

        viewModel.dataList.observe(viewLifecycleOwner) { data ->
            binding.progressRing.isVisible = false
            refreshItems(viewModel.exchangeRate.value, data)
        }
    }

    fun submitList(accounts: List<CoinBaseUserAccountDataUIModel>) {
        viewModel.submitList(accounts)
    }

    private fun refreshItems(rate: ExchangeRate?, dataList: List<CoinBaseUserAccountDataUIModel>) {
        itemList = dataList.map {
            val accountData = it.coinBaseUserAccountData
            val icon = getFlagFromCurrencyCode(accountData.currency?.code ?: "")
            val iconUrl = if (icon == null && !accountData.currency?.code.isNullOrEmpty()) {
                "https://raw.githubusercontent.com/jsupa/crypto-icons/main/icons/" +
                    "${accountData.currency?.code?.lowercase()}.png"
            } else {
                null
            }

            val cryptoCurrencyBalance =
                if (accountData.balance?.amount.isNullOrEmpty() || accountData.balance?.amount?.toDouble() == 0.0) {
                    MonetaryFormat().withLocale(GenericUtils.getDeviceLocale())
                        .noCode().minDecimals(2).optionalDecimals().format(Coin.ZERO).toString()
                } else {
                    accountData.balance?.amount
                }

            IconifiedViewItem(
                accountData.currency?.code ?: "",
                accountData.currency?.name ?: "",
                icon,
                iconUrl,
                IconSelectMode.None,
                setLocalFaitAmount(rate, it)?.first,
                subtitleAdditionalInfo = cryptoCurrencyBalance
            )
        }

        val adapter = adapter

        if (adapter != null && itemList.isNotEmpty()) {
            if (!didFocusOnSelected) {
                lifecycleScope.launch {
                    delay(250)
                    adapter.submitList(itemList)
                    val selectedRateIndex =
                        itemList.indexOfFirst { it.title == selectedCurrencyCode }
                    adapter.selectedIndex = selectedRateIndex
                    binding.contentList.scrollToPosition(selectedRateIndex)
                    didFocusOnSelected = true
                }
            } else {
                val list = if (binding.searchQuery.text.isNullOrBlank()) {
                    itemList
                } else {
                    filterByQuery(itemList, binding.searchQuery.text.toString())
                }
                val layoutManager = binding.contentList.layoutManager as LinearLayoutManager
                val scrollPosition = layoutManager.findFirstVisibleItemPosition()
                adapter.submitList(list)
                binding.contentList.scrollToPosition(scrollPosition)
            }
        }
    }

    private fun setLocalFaitAmount(
        currentExchangeRate: ExchangeRate?,
        coinBaseUserAccountData: CoinBaseUserAccountDataUIModel
    ): Pair<String, Coin>? {
        currentExchangeRate?.let {
            return coinBaseUserAccountData.getCoinBaseExchangeRateConversion(it)
        }

        return null
    }

    override fun dismiss() {
        lifecycleScope.launch {
            delay(300)
            super.dismiss()
        }
    }

    private fun filterByQuery(
        items: List<IconifiedViewItem>,
        query: String
    ): List<IconifiedViewItem> {
        return items.filter {
            it.title.lowercase().contains(query.lowercase()) ||
                it.additionalInfo?.lowercase()?.contains(query.lowercase()) == true
        }
    }

    private fun getFlagFromCurrencyCode(currencyCode: String): Int? {
        val resourceId = resources.getIdentifier(
            "currency_code_" + currencyCode.lowercase(),
            "drawable",
            requireContext().packageName
        )
        return if (resourceId == 0) null else resourceId
    }
}
