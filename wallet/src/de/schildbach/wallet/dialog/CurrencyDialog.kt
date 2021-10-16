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

package de.schildbach.wallet.dialog

import android.content.Context
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.dash.wallet.integration.liquid.listener.CurrencySelectListener
import org.dash.wallet.integration.liquid.listener.ValueSelectListener
import org.dash.wallet.integration.liquid.currency.PayloadItem
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.common.base.Strings
import de.schildbach.wallet.adapter.CurrencyAdapter
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.DialogLiquidAllCurrienciesBinding
import org.dash.wallet.integration.uphold.currencyModel.UpholdCurrencyResponse
import java.util.*
import kotlin.collections.ArrayList


class CurrencyDialog(
    val activity: Context,
    private val liquidCurrencyArrayList: ArrayList<PayloadItem>,
    private val upholdCurrencyArrayList: ArrayList<UpholdCurrencyResponse>,
    private val selectedFilterCurrencyItem: PayloadItem?,
    val listener: CurrencySelectListener
) : BottomSheetDialog(activity) {

    private lateinit var viewBinding: DialogLiquidAllCurrienciesBinding

    init {
        setCancelable(true)
        setCanceledOnTouchOutside(true)
        create()
    }

    override fun create() {

        val currencyArrayList = ArrayList<PayloadItem>()

        for (i in liquidCurrencyArrayList.indices) {
            if (liquidCurrencyArrayList[i].type == "FIAT") {

                var isAddedToList = false
                if (liquidCurrencyArrayList[i].settlement?.funding?.contains("CARD_PAYMENT") == true) {
                    currencyArrayList.add(
                        PayloadItem(
                            liquidCurrencyArrayList[i].ccyCode,
                            liquidCurrencyArrayList[i].icon,
                            liquidCurrencyArrayList[i].label,
                            "Liquid"
                        )
                    )
                    isAddedToList = true
                }

                if (!isAddedToList) {
                    if (liquidCurrencyArrayList[i].settlement?.payout?.contains("LIQUID_USER_WALLET") == true) {
                        currencyArrayList.add(
                            PayloadItem(
                                liquidCurrencyArrayList[i].ccyCode,
                                liquidCurrencyArrayList[i].icon,
                                liquidCurrencyArrayList[i].label,
                                "Liquid"
                            )
                        )
                    }
                }
            }
        }

        for (i in upholdCurrencyArrayList.indices) {
            if (upholdCurrencyArrayList[i].type == "fiat") {
                currencyArrayList.add(
                    PayloadItem(
                        upholdCurrencyArrayList[i].code,
                        "",
                        upholdCurrencyArrayList[i].name,
                        "Uphold"
                    )
                )
            }
        }


        viewBinding = DialogLiquidAllCurrienciesBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(activity, R.style.BottomSheetDialog) // Style here
        dialog.setContentView(viewBinding.root)
        dialog.show()

        viewBinding.rvCurrency.layoutManager = LinearLayoutManager(activity)

        val currencyAdapter =
            CurrencyAdapter(LayoutInflater.from(activity), currencyArrayList, object : ValueSelectListener {
                override fun onItemSelected(value: Int) {

                    var isUpholdSupport = false
                    var isLiquidSupport = false

                    val item = currencyArrayList[value]
                    if (item.type == "Liquid") {
                        isLiquidSupport = true
                    } else {
                        isUpholdSupport = true
                    }

                    for (i in currencyArrayList.indices) {
                        if (isLiquidSupport) {
                            if ((item.symbol.equals(
                                    currencyArrayList[i].symbol,
                                    ignoreCase = true
                                )) and (currencyArrayList[i].type == "Uphold")
                            ) {
                                isUpholdSupport = true
                                break
                            }
                        } else if (isUpholdSupport) {
                            if ((item.symbol.equals(
                                    currencyArrayList[i].symbol,
                                    ignoreCase = true
                                )) and (currencyArrayList[i].type == "Liquid")
                            ) {
                                isLiquidSupport = true
                                break
                            }
                        }
                    }


                    listener.onCurrencySelected(isLiquidSupport, isUpholdSupport, item)
                    Timer().schedule(object : TimerTask() {
                        override fun run() {
                            dialog.dismiss()
                        }
                    }, 1000)

                }
            })
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
            viewBinding.txtClearFilter.visibility = View.GONE
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
    }

    private fun updateCloseButton() {
        val hasText = !TextUtils.isEmpty(viewBinding.currencySearch.getText())
        // Should we show the close button? It is not shown if there's no focus,
        // field is not iconified by default and there is no text in it.
        viewBinding.searchCloseBtn.isVisible = hasText
        viewBinding.headerLayout.isVisible = !hasText
    }

}