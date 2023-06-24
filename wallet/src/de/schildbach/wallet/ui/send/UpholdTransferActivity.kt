/*
 * Copyright 2019 Dash Core Group.
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

package de.schildbach.wallet.ui.send

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.view.Gravity
import android.view.MenuItem
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.widget.Toolbar
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet_test.R
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.InteractionAwareActivity
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.ui.enter_amount.EnterAmountFragment
import org.dash.wallet.common.ui.enter_amount.EnterAmountViewModel
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.openCustomTab
import org.dash.wallet.integration.uphold.data.RequirementsCheckResult
import org.dash.wallet.integration.uphold.data.UpholdConstants
import org.dash.wallet.integration.uphold.data.UpholdTransaction
import org.dash.wallet.integration.uphold.ui.UpholdWithdrawalHelper
import org.dash.wallet.integration.uphold.ui.UpholdWithdrawalHelper.OnTransferListener
import java.math.BigDecimal
import javax.inject.Inject

@AndroidEntryPoint
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

    private val enterAmountViewModel by viewModels<EnterAmountViewModel>()
    @Inject lateinit var walletDataProvider: WalletDataProvider
    @Inject lateinit var transactionMetadataProvider: TransactionMetadataProvider
    private lateinit var balance: Coin
    private lateinit var withdrawalDialog: UpholdWithdrawalHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_uphold_tranfser)

        val balanceStr = intent.getStringExtra(EXTRA_MAX_AMOUNT)
        balance = Coin.parseCoin(balanceStr)

        if (savedInstanceState == null) {
            val fragment = EnterAmountFragment.newInstance()
            supportFragmentManager.beginTransaction()
                    .replace(R.id.container, fragment)
                    .commitNow()

            val drawableDash = ResourcesCompat.getDrawable(resources, R.drawable.ic_dash_d_black, null)
            drawableDash!!.setBounds(0, 0, 38, 38)
            val dashSymbol = ImageSpan(drawableDash, ImageSpan.ALIGN_BASELINE)
            val builder = SpannableStringBuilder()
            builder.appendLine(intent.getStringExtra(EXTRA_MESSAGE))
            builder.append("  ")
            builder.setSpan(dashSymbol, builder.length - 2, builder.length - 1, 0)
            val dashFormat = MonetaryFormat().noCode().minDecimals(6).optionalDecimals()
            builder.append(dashFormat.format(balance))
            builder.append("  ")
            builder.append(getText(R.string.enter_amount_available))

            fragment.setViewDetails(
                getString(R.string.uphold_transfer),
                TextView(this).apply {
                    text = SpannableString.valueOf(builder)
                    gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
                }
            )
        }

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        val actionBar = supportActionBar
        actionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }

        title = intent.getStringExtra(EXTRA_TITLE)

        enterAmountViewModel.setMaxAmount(balance)

        enterAmountViewModel.onContinueEvent.observe(this) {
            UpholdWithdrawalHelper.requirementsSatisfied(this) { result ->
                when (result) {
                    RequirementsCheckResult.Satisfied -> {
                        val dashAmount = it.first
                        showPaymentConfirmation(dashAmount)
                    }
                    RequirementsCheckResult.Resolve -> {
                        this.openCustomTab(UpholdConstants.PROFILE_URL)
                        super.turnOffAutoLogout()
                    }
                    else -> {}
                }
            }
        }
    }

    private fun showPaymentConfirmation(amount: Coin) {
        val receiveAddress = walletDataProvider.freshReceiveAddress()

        withdrawalDialog = UpholdWithdrawalHelper(BigDecimal(balance.toPlainString()), object : OnTransferListener {
            override fun onConfirm(transaction: UpholdTransaction) {
                val address: String = receiveAddress.toBase58()
                val amountStr = transaction.origin.base.toPlainString()

                // if the exchange rate is not available, then show "Not Available"
                val exchangeRate = enterAmountViewModel.selectedExchangeRate.value?.let { ExchangeRate(Coin.COIN, it.fiat) }
                val fiatAmount = exchangeRate?.coinToFiat(amount)
                val amountFiat = if (fiatAmount != null) Constants.LOCAL_FORMAT.format(fiatAmount).toString() else getString(R.string.transaction_row_rate_not_available)
                val fiatSymbol = if (fiatAmount != null) GenericUtils.currencySymbol(fiatAmount.currencyCode) else ""

                val fee = transaction.origin.fee.toPlainString()
                val total = transaction.origin.amount.toPlainString()
                lifecycleScope.launch {
                    val toContinue = ConfirmTransactionDialog.showDialogAsync(this@UpholdTransferActivity, address, amountStr, amountFiat, fiatSymbol, fee, total)

                    if (toContinue) {
                        withdrawalDialog.commitTransaction(this@UpholdTransferActivity)
                    }
                }
            }

            override fun onTransfer() {
                transactionMetadataProvider.markAddressAsTransferInAsync(receiveAddress.toBase58(), ServiceName.Uphold)
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
