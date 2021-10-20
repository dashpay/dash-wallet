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

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import org.dash.wallet.integration.liquid.currency.PayloadItem
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.common.base.Strings
import org.dash.wallet.integration.liquid.R
import org.dash.wallet.integration.liquid.adapter.CurrencyAdapter
import org.dash.wallet.integration.liquid.databinding.DialogSearchCurrienciesBinding
import kotlin.collections.ArrayList


abstract class CurrencySearchDialog(
    val activity: Activity,
    private val selectedFilterCurrencyItem: PayloadItem?,
    private val titleResId: Int
) : BottomSheetDialog(activity) {

    protected val currencyArrayList: ArrayList<PayloadItem> = arrayListOf()
    protected lateinit var viewBinding: DialogSearchCurrienciesBinding
    protected lateinit var currencyAdapter: CurrencyAdapter
    protected lateinit var dialog: Dialog
    protected var expandedSheet = false

    open fun generateList() {
    }

    abstract fun createAdapter() : CurrencyAdapter

    override fun create() {
        setCancelable(true)
        setCanceledOnTouchOutside(true)
        viewBinding = DialogSearchCurrienciesBinding.inflate(layoutInflater)
        dialog = BottomSheetDialog(activity, R.style.BottomSheetDialog) // Style here
        dialog.setContentView(viewBinding.root)

        generateList()

        viewBinding.apply {
            currencyList.layoutManager = LinearLayoutManager(activity)
            currencyAdapter = createAdapter()
            currencyList.adapter = currencyAdapter

            if (selectedFilterCurrencyItem != null) {
                for (i in currencyArrayList.indices) {
                    if ((selectedFilterCurrencyItem.symbol.equals(
                            currencyArrayList[i].symbol,
                            ignoreCase = true
                        ))
                    ) {
                        currencyAdapter.setSelectedPosition(i, selectedFilterCurrencyItem)
                        currencyList.scrollToPosition(i)

                        clearFilter.visibility = View.VISIBLE
                        break
                    }
                }
            }
            currencySearch.doOnTextChanged { text, _, _, _ ->
                val query = Strings.emptyToNull(text.toString().trim { it <= ' ' }) ?: ""
                updateView()
                currencyAdapter.filter(query)
            }
            /*currencySearch.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                }

                override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {

                }

                override fun afterTextChanged(view: Editable?) {
                }
            })*/

            currencySearch.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    expandSheet()
                }
            }
            closeButton.setOnClickListener {
                dialog.dismiss()
            }
            cancelSearch.setOnClickListener {
                val imm =
                    activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                currencySearch.setText("")
                currencySearch.requestFocus()
                if (imm.isActive) {
                    imm.showSoftInput(it, 0)
                }
            }
            searchCloseBtn.setOnClickListener {
                val imm =
                    activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                currencySearch.setText("")
                currencySearch.clearFocus()
                if (imm.isActive) {
                    imm.hideSoftInputFromWindow(it.windowToken, 0)
                }
                dialog.dismiss()
            }
            selectTitle.text = context.getString(titleResId)

        }
        setViewPaddingAndBackground()
        dialog.show()
    }

    private fun expandSheet() {
        val bottomSheetDialog = dialog
        val parentLayout =
            bottomSheetDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        parentLayout?.let { it ->
            val behaviour = BottomSheetBehavior.from(it)
            setupFullHeight(it)
            behaviour.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun setupFullHeight(bottomSheet: View) {
        val layoutParams = bottomSheet.layoutParams
        layoutParams.height = WindowManager.LayoutParams.MATCH_PARENT
        bottomSheet.layoutParams = layoutParams
    }

    private fun updateView() {
        val hasText = !TextUtils.isEmpty(viewBinding.currencySearch.text)
        if (hasText) {
            expandSheet()
        }

        viewBinding.apply {
            searchCloseBtn.isVisible = hasText
            cancelSearch.isVisible = hasText
            headerLayout.isVisible = !hasText
        }
    }

    private fun setViewPaddingAndBackground() {
        val paddingTopDp = 16
        val scale: Float = activity.resources.displayMetrics.density
        val sizeInDp = (paddingTopDp * scale).toInt()
        viewBinding.viewContainer.setPadding(0, sizeInDp, 0, 0)
        viewBinding.viewContainer.setBackgroundResource(R.drawable.background_dialog)
    }
}