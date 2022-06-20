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

package de.schildbach.wallet.ui.transactions

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Animatable
import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.ui.AbstractWalletActivity
import de.schildbach.wallet.ui.ReportIssueDialogBuilder
import de.schildbach.wallet.ui.main.WalletActivity
import de.schildbach.wallet.ui.send.SendCoinsInternalActivity.ACTION_SEND_FROM_WALLET_URI
import de.schildbach.wallet.util.WalletUtils
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_successful_transaction.*
import kotlinx.android.synthetic.main.transaction_result_content.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.slf4j.LoggerFactory

/**
 * @author Samuel Barbosa
 */
@FlowPreview
@ExperimentalCoroutinesApi
class TransactionResultActivity : AbstractWalletActivity() {

    private val log = LoggerFactory.getLogger(javaClass.simpleName)

    companion object {
        const val EXTRA_TX_ID = "tx_id"
        const val EXTRA_USER_AUTHORIZED_RESULT_EXTRA = "user_authorized_result_extra"
        private const val EXTRA_PAYMENT_MEMO = "payee_name"
        private const val EXTRA_PAYEE_VERIFIED_BY = "payee_verified_by"

        @JvmStatic
        fun createIntent(context: Context, action: String? = null, transaction: Transaction, userAuthorized: Boolean): Intent {
            return createIntent(context, action, transaction, userAuthorized, null, null)
        }

        @JvmStatic
        fun createIntent(context: Context, transaction: Transaction, userAuthorized: Boolean, payeeName: String? = null,
                         payeeVerifiedBy: String? = null): Intent {
            return createIntent(context, null, transaction, userAuthorized, payeeName, payeeVerifiedBy)
        }

        fun createIntent(context: Context, action: String?, transaction: Transaction, userAuthorized: Boolean,
                         paymentMemo: String? = null, payeeVerifiedBy: String? = null): Intent {
            return Intent(context, TransactionResultActivity::class.java).apply {
                setAction(action)
                putExtra(EXTRA_TX_ID, transaction.txId)
                putExtra(EXTRA_USER_AUTHORIZED_RESULT_EXTRA, userAuthorized)
                putExtra(EXTRA_PAYMENT_MEMO, paymentMemo)
                putExtra(EXTRA_PAYEE_VERIFIED_BY, payeeVerifiedBy)
            }
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val txId = intent.getSerializableExtra(EXTRA_TX_ID) as Sha256Hash
        if (intent.extras?.getBoolean(EXTRA_USER_AUTHORIZED_RESULT_EXTRA, false)!!)
            intent.putExtra(INTENT_EXTRA_KEEP_UNLOCKED, true)

        setContentView(R.layout.activity_successful_transaction)

        val transactionResultViewBinder = TransactionResultViewBinder(
            walletData.wallet!!,
            configuration.format.noCode(),
            container
        )
        val tx = walletData.wallet!!.getTransaction(txId)
        if (tx != null) {
            val payeeName = intent.getStringExtra(EXTRA_PAYMENT_MEMO)
            val payeeVerifiedBy = intent.getStringExtra(EXTRA_PAYEE_VERIFIED_BY)
            transactionResultViewBinder.bind(tx, payeeName, payeeVerifiedBy)
            open_explorer_card.setOnClickListener { viewOnExplorer(tx) }
            transaction_close_btn.setOnClickListener {
                onTransactionDetailsDismiss()
            }
            report_issue_card.setOnClickListener {
                showReportIssue()
            }
        } else {
            log.error("Transaction not found. TxId:", txId)
            finish()
            return
        }

        check_icon.setImageDrawable(ContextCompat.getDrawable(this,
                R.drawable.check_animated))
        check_icon.postDelayed({
            check_icon.visibility = View.VISIBLE
            (check_icon.drawable as Animatable).start()
        }, 400)
    }

    private fun viewOnExplorer(tx: Transaction) {
        WalletUtils.viewOnBlockExplorer(this, tx.purpose, tx.txId.toString())
    }

    private fun showReportIssue() {
        ReportIssueDialogBuilder.createReportIssueDialog(this, WalletApplication.getInstance())
            .buildAlertDialog().show()
    }

    private fun onTransactionDetailsDismiss(){
        when {
            intent.action == Intent.ACTION_VIEW ||
                    intent.action == ACTION_SEND_FROM_WALLET_URI -> {
                finish()
            }
            intent.getBooleanExtra(EXTRA_USER_AUTHORIZED_RESULT_EXTRA, false) -> {
                startActivity(WalletActivity.createIntent(this))
            }
            else -> {
                startActivity(WalletActivity.createIntent(this))
            }
        }
    }
}