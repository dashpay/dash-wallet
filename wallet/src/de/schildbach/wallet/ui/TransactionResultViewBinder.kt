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

package de.schildbach.wallet.ui

import android.text.format.DateUtils
import android.view.View
import android.widget.TextView
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.TransactionResult
import de.schildbach.wallet.util.WalletUtils
import de.schildbach.wallet_test.R
import org.bitcoinj.core.Address
import org.bitcoinj.core.AddressFormatException
import org.dash.wallet.common.ui.CurrencyTextView

/**
 * @author Samuel Barbosa
 */
class TransactionResultViewBinder(private val containerView: View) {

    private val dashAmount: CurrencyTextView by lazy { containerView.findViewById<CurrencyTextView>(R.id.dash_amount) }
    private val transactionFee: CurrencyTextView by lazy { containerView.findViewById<CurrencyTextView>(R.id.transaction_fee) }
    private val transactionAddress: TextView by lazy { containerView.findViewById<TextView>(R.id.transaction_address) }
    private val fiatValue: CurrencyTextView by lazy { containerView.findViewById<CurrencyTextView>(R.id.fiat_value) }
    private val date: TextView by lazy { containerView.findViewById<TextView>(R.id.transaction_date_and_time) }
    private val primaryStatus: TextView by lazy { containerView.findViewById<TextView>(R.id.transaction_primary_status) }
    private val secondaryStatus: TextView by lazy { containerView.findViewById<TextView>(R.id.transaction_secondary_status) }

    fun bind(transactionResult: TransactionResult) {
        val noCodeFormat = WalletApplication.getInstance().configuration.format.noCode()

        dashAmount.setFormat(noCodeFormat)
        //For displaying purposes only
        if (transactionResult.dashAmount.isNegative) {
            dashAmount.setAmount(transactionResult.dashAmount.negate())
        } else {
            dashAmount.setAmount(transactionResult.dashAmount)
        }

        transactionFee.setFormat(noCodeFormat)
        transactionFee.setAmount(transactionResult.feeAmount)

        try {
            // Determine if address is valid
            Address.fromString(Constants.NETWORK_PARAMETERS, transactionResult.address)
            transactionAddress.text = WalletUtils.buildShortAddress(transactionResult.address)
        } catch (x : AddressFormatException) {
            // Display the address as regular text
            transactionAddress.text = transactionResult.address
        }
        date.text = DateUtils.formatDateTime(containerView.context, transactionResult.date.time,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)

        val exchangeRate = transactionResult.exchangeRate
        val exchangeCurrencyCode = WalletApplication.getInstance().configuration
                .exchangeCurrencyCode
        fiatValue.setFiatAmount(transactionResult.dashAmount, exchangeRate, Constants.LOCAL_FORMAT,
                exchangeCurrencyCode)

        // transaction status
        if(transactionResult.errorStatus.isNotEmpty()) {
            //set colors to red
            val colorError = containerView.context.resources.getColor(R.color.fg_error)
            primaryStatus.setTextColor(colorError)
            secondaryStatus.setTextColor(colorError)
            primaryStatus.text = containerView.context.getString(R.string.transaction_row_status_error_sending)
            secondaryStatus.text = transactionResult.errorStatus

        } else {
            if(transactionResult.primaryStatus.isNotEmpty()) {
                primaryStatus.text = transactionResult.primaryStatus
            } else {
                primaryStatus.visibility = View.GONE
            }
            if(transactionResult.secondaryStatus.isNotEmpty())
                secondaryStatus.text = transactionResult.secondaryStatus
            else secondaryStatus.visibility = View.GONE
        }
    }

}