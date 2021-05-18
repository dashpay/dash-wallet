package de.schildbach.wallet.dialog

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.dash.wallet.integration.liquid.listener.CurrencySelectListener
import org.dash.wallet.integration.liquid.listener.ValueSelectListener
import org.dash.wallet.integration.liquid.currency.PayloadItem
import com.google.android.material.bottomsheet.BottomSheetDialog
import de.schildbach.wallet.adapter.CurrencyAdapter
import de.schildbach.wallet_test.R
import org.dash.wallet.integration.uphold.currencyModel.UpholdCurrencyResponse
import java.util.*
import kotlin.collections.ArrayList


class CurrencyDialog(val _context: Context, private val liquidCurrencyArrayList: ArrayList<PayloadItem>, private val upholdCurrencyArrayList: ArrayList<UpholdCurrencyResponse>, val selectedFilterCurrencyItem: PayloadItem?, val listener: CurrencySelectListener) : BottomSheetDialog(_context) {
    private var rvCurrency: RecyclerView? = null
    private var txtClearFilter: TextView? = null

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
                    currencyArrayList.add(PayloadItem(liquidCurrencyArrayList[i].ccyCode, "", liquidCurrencyArrayList[i].label, "Liquid"))
                    isAddedToList = true
                }

                if (!isAddedToList) {
                    if (liquidCurrencyArrayList[i].settlement?.payout?.contains("LIQUID_USER_WALLET") == true) {
                        currencyArrayList.add(PayloadItem(liquidCurrencyArrayList[i].ccyCode, "", liquidCurrencyArrayList[i].label, "Liquid"))
                    }
                }
            }
        }

        for (i in upholdCurrencyArrayList.indices) {
            if (upholdCurrencyArrayList[i].type == "fiat") {
                currencyArrayList.add(PayloadItem(upholdCurrencyArrayList[i].code, "", upholdCurrencyArrayList[i].name, "Uphold"))
            }
        }


        val bottomSheetView = layoutInflater.inflate(R.layout.dialog_liquid_all_curriencies, null)
        val dialog = BottomSheetDialog(_context, R.style.BottomSheetDialog) // Style here
        dialog.setContentView(bottomSheetView);
        dialog.show()


        rvCurrency = bottomSheetView.findViewById(R.id.rvCurrency)
        txtClearFilter = bottomSheetView.findViewById(R.id.txtClearFilter)
        rvCurrency?.layoutManager = LinearLayoutManager(_context)

        val currencyAdapter = CurrencyAdapter(_context, currencyArrayList, object : ValueSelectListener {
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
                        if ((item.symbol.equals(currencyArrayList[i].symbol, ignoreCase = true)) and (currencyArrayList[i].type == "Uphold")) {
                            isUpholdSupport = true
                            break
                        }
                    } else if (isUpholdSupport) {
                        if ((item.symbol.equals(currencyArrayList[i].symbol, ignoreCase = true)) and (currencyArrayList[i].type == "Liquid")) {
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
        rvCurrency?.adapter = currencyAdapter

        if (selectedFilterCurrencyItem != null) {

            for (i in currencyArrayList.indices) {
                if ((selectedFilterCurrencyItem.symbol.equals(currencyArrayList[i].symbol, ignoreCase = true))) {
                    currencyAdapter.setSelectedPositions(i)
                    txtClearFilter?.visibility = View.VISIBLE
                    break
                }
            }

        }

        txtClearFilter?.setOnClickListener {
            currencyAdapter.setSelectedPositions(-1)
            txtClearFilter?.visibility = View.GONE
            listener.onCurrencySelected(true, true, null)
        }
    }
}