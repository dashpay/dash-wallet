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

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.view.MenuItem
import androidx.appcompat.widget.Toolbar
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.ui.send.ConfirmTransactionDialog
import de.schildbach.wallet.ui.send.EnterAmountSharedViewModel
import de.schildbach.wallet_test.R
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.InteractionAwareActivity
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.integration.uphold.data.UpholdTransaction
import org.dash.wallet.integration.uphold.ui.UpholdWithdrawalHelper
import org.dash.wallet.integration.uphold.ui.UpholdWithdrawalHelper.OnTransferListener
import java.math.BigDecimal

class UpholdTransferActivity : InteractionAwareActivity() {

    companion object {

        private const val EXTRA_MAX_AMOUNT = "extra_max_amount"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_MESSAGE = "extra_message"

        fun createIntent(context: Context, title: String, message: CharSequence, maxAmount: String): Intent {
            val intent = Intent(context, UpholdTransferActivity::class.java)
            intent.putExtra(EXTRA_TITLE, title)
            intent.putExtra(EXTRA_MESSAGE, message)
            intent.putExtra(EXTRA_MAX_AMOUNT, maxAmount)
            return intent
        }
    }

    private lateinit var enterAmountSharedViewModel: EnterAmountSharedViewModel
    private lateinit var balance: Coin

    private lateinit var withdrawalDialog: UpholdWithdrawalHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_uphold_tranfser)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                    .replace(R.id.container, EnterAmountFragment.newInstance(Coin.ZERO))
                    .commitNow()
        }

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        val actionBar = supportActionBar
        actionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }

        title = intent.getStringExtra(EXTRA_TITLE)

        enterAmountSharedViewModel = ViewModelProviders.of(this).get(EnterAmountSharedViewModel::class.java)
        enterAmountSharedViewModel.buttonTextData.call(R.string.uphold_transfer)

        val balanceStr = intent.getStringExtra(EXTRA_MAX_AMOUNT)
        balance = Coin.parseCoin(balanceStr)

        val drawableDash = ResourcesCompat.getDrawable(resources, R.drawable.ic_dash_d_black, null)
        drawableDash!!.setBounds(0, 0, 38, 38)
        val dashSymbol = ImageSpan(drawableDash, ImageSpan.ALIGN_BASELINE)
        val builder = SpannableStringBuilder()
        builder.appendln(intent.getStringExtra(EXTRA_MESSAGE))
        builder.append("  ")
        builder.setSpan(dashSymbol, builder.length - 2, builder.length - 1, 0)
        val dashFormat = MonetaryFormat().noCode().minDecimals(6).optionalDecimals()
        builder.append(dashFormat.format(balance))
        builder.append("  ")
        builder.append(getText(R.string.enter_amount_available))

        enterAmountSharedViewModel.messageTextStringData.value = SpannableString.valueOf(builder)
        enterAmountSharedViewModel.buttonClickEvent.observe(this, Observer {
            val dashAmount = enterAmountSharedViewModel.dashAmount
            showPaymentConfirmation(dashAmount)
        })
        enterAmountSharedViewModel.maxButtonVisibleData.value = true
        enterAmountSharedViewModel.maxButtonClickEvent.observe(this, Observer<Boolean?> {
            enterAmountSharedViewModel.applyMaxAmountEvent.setValue(balance)
        })
        enterAmountSharedViewModel.dashAmountData.observe(this, Observer<Coin> {
            enterAmountSharedViewModel.buttonEnabledData.setValue(it.isPositive)
        })
        val confirmTransactionSharedViewModel: SingleActionSharedViewModel = ViewModelProviders.of(this).get(SingleActionSharedViewModel::class.java)
        confirmTransactionSharedViewModel.clickConfirmButtonEvent.observe(this, Observer {
            withdrawalDialog.commitTransaction(this)
        })
    }

    private fun showPaymentConfirmation(amount: Coin) {

        val application = application as WalletApplication
        val receiveAddress = application.wallet.freshReceiveAddress()

        withdrawalDialog = UpholdWithdrawalHelper(BigDecimal(balance.toPlainString()), object : OnTransferListener {
            override fun onConfirm(transaction: UpholdTransaction) {
                val address: String = receiveAddress.toBase58()
                val amountStr = transaction.origin.base.toPlainString()

                // if the exchange rate is not available, then show "Not Available"
                val fiatAmount = enterAmountSharedViewModel.exchangeRate?.coinToFiat(amount)
                val amountFiat = if (fiatAmount != null) Constants.LOCAL_FORMAT.format(fiatAmount).toString() else getString(R.string.transaction_row_rate_not_available)
                val fiatSymbol = if (fiatAmount != null) GenericUtils.currencySymbol(fiatAmount.currencyCode) else ""

                val fee = transaction.origin.fee.toPlainString()
                val total = transaction.origin.amount.toPlainString()
                val dialog = ConfirmTransactionDialog.createDialog(address, amountStr, amountFiat, fiatSymbol, fee, total, getString(R.string.uphold_transfer))
                dialog.show(supportFragmentManager, "ConfirmTransactionDialog")
            }

            override fun onTransfer() {
                finish()
            }

        })
        withdrawalDialog.transfer(this, receiveAddress.toBase58(), BigDecimal(amount.toPlainString()), false)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                return true
            }
        }

        return super.onOptionsItemSelected(item)
    }
}
