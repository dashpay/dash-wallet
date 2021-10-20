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

import android.app.Activity
import androidx.core.view.isVisible
import org.dash.wallet.integration.liquid.R
import org.dash.wallet.integration.liquid.currency.PayloadItem
import org.dash.wallet.integration.liquid.adapter.CurrencyAdapter
import org.dash.wallet.integration.liquid.dialog.CurrencySearchDialog
import org.dash.wallet.integration.uphold.currencyModel.UpholdCurrencyResponse
import java.util.*
import kotlin.collections.ArrayList

class FiatCurrencySearchDialog(
    activity: Activity,
    private val liquidCurrencyArrayList: ArrayList<PayloadItem>,
    private val upholdCurrencyArrayList: ArrayList<UpholdCurrencyResponse>,
    selectedFilterCurrencyItem: PayloadItem?,
    val currencySelectListener: (Boolean, Boolean, PayloadItem?) -> Unit
) : CurrencySearchDialog(activity, selectedFilterCurrencyItem, R.string.select_fiat_currency) {

    override fun create() {
        super.create()
        viewBinding.clearFilter.setOnClickListener {
            currencyAdapter.setSelectedPosition(-1, null)
            viewBinding.clearFilter.isVisible = false
            currencySelectListener(true, true, null)
        }
    }

    override fun generateList() {
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
    }

    override fun createAdapter(): CurrencyAdapter {
        return CurrencyAdapter(activity, currencyArrayList) { value ->

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

            currencySelectListener(isLiquidSupport, isUpholdSupport, item)
            Timer().schedule(object : TimerTask() {
                override fun run() {
                    dialog.dismiss()
                }
            }, 1000)
        }
    }
}