/*
 * Copyright 2021 the original author or authors.
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

package org.dash.wallet.integration.liquid.dialog

import android.app.Dialog
import android.content.Context
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.dash.wallet.integration.liquid.listener.CurrencySelectListener
import org.dash.wallet.integration.liquid.listener.ValueSelectListener
import org.dash.wallet.integration.liquid.currency.PayloadItem
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.common.base.Strings
import org.dash.wallet.integration.liquid.R
import org.dash.wallet.integration.liquid.adapter.CurrencyAdapter
import org.dash.wallet.integration.liquid.databinding.DialogLiquidAllCurrienciesBinding
import kotlin.collections.ArrayList


abstract class CurrencyDialog(
    val activity: Context,
    private val selectedFilterCurrencyItem: PayloadItem?,
    val listener: CurrencySelectListener
) : BottomSheetDialog(activity) {


    protected val currencyArrayList: ArrayList<PayloadItem> = arrayListOf()
    private lateinit var viewBinding: DialogLiquidAllCurrienciesBinding
    protected lateinit var currencyAdapter: CurrencyAdapter
    protected lateinit var dialog: Dialog

    open fun generateList() {
    }

    abstract fun createAdapter() : CurrencyAdapter

    override fun create() {
        setCancelable(true)
        setCanceledOnTouchOutside(true)
        viewBinding = DialogLiquidAllCurrienciesBinding.inflate(layoutInflater)
        dialog = BottomSheetDialog(activity, R.style.BottomSheetDialog) // Style here
        dialog.setContentView(viewBinding.root)

        generateList()

        viewBinding.rvCurrency.layoutManager = LinearLayoutManager(activity)
        currencyAdapter = createAdapter()
        viewBinding.rvCurrency.adapter = currencyAdapter

        if (selectedFilterCurrencyItem != null) {
            for (i in currencyArrayList.indices) {
                if ((selectedFilterCurrencyItem.symbol.equals(
                        currencyArrayList[i].symbol,
                        ignoreCase = true
                    ))
                ) {
                    currencyAdapter.setSelectedPositions(i)
                    viewBinding.txtClearFilter.visibility = View.VISIBLE
                    break
                }
            }
        }

        viewBinding.txtClearFilter.setOnClickListener {
            currencyAdapter.setSelectedPositions(-1)
            viewBinding.txtClearFilter.isVisible = false
            listener.onCurrencySelected(true, true, null)
        }

        viewBinding.currencySearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                val query = Strings.emptyToNull(s.toString().trim { it <= ' ' }) ?: ""
                updateCloseButton()
                currencyAdapter.filter(query)
            }

            override fun afterTextChanged(view: Editable?) {
            }
        })

        dialog.show()
    }

    private fun updateCloseButton() {
        val hasText = !TextUtils.isEmpty(viewBinding.currencySearch.text)
        // Should we show the close button? It is not shown if there's no focus,
        // field is not iconified by default and there is no text in it.
        viewBinding.searchCloseBtn.isVisible = hasText
        viewBinding.headerLayout.isVisible = !hasText
    }
}