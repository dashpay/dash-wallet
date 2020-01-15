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

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Animatable
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.TransactionResult
import de.schildbach.wallet.util.TransactionUtil
import de.schildbach.wallet.util.WalletUtils
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_successful_transaction.*
import org.bitcoinj.core.Address
import org.bitcoinj.core.Transaction


/**
 * @author Samuel Barbosa
 */
class TransactionResultActivity : AbstractWalletActivity() {

    private lateinit var transactionResult: TransactionResult

    companion object {
        const val TRANSACTION_RESULT_EXTRA = "transaction_result_extra"

        @JvmStatic
        fun createIntent(context: Context, transaction: Transaction, address: Address): Intent {
            val wallet = WalletApplication.getInstance().wallet

            // obtain the transaction status
            val primaryStatus = TransactionUtil.getTransactionTypeName(transaction, wallet)
            val secondaryStatus = TransactionUtil.getReceivedStatusString(transaction, wallet)
            val errorStatus = TransactionUtil.getErrorName(transaction)
            var primaryStatusStr = if (transaction.type != Transaction.Type.TRANSACTION_NORMAL || transaction.isCoinBase) context.getString(primaryStatus) else ""
            var secondaryStatusStr = if (secondaryStatus != -1) context.getString(secondaryStatus) else ""
            val errorStatusStr = if (errorStatus != -1) context.getString(errorStatus) else ""

            // handle sending
            if(TransactionUtil.isSending(transaction, wallet)) {
                primaryStatusStr = context.getString(R.string.transaction_row_status_sending)
                secondaryStatusStr = ""
            }

            val transactionResult = TransactionResult(
                    transaction.getValue(wallet), transaction.exchangeRate, address.toString(),
                    transaction.fee, transaction.txId.toString(), transaction.updateTime,
                    transaction.purpose, primaryStatusStr, secondaryStatusStr, errorStatusStr)

            val transactionResultIntent = Intent(context, TransactionResultActivity::class.java)
            transactionResultIntent.putExtra(TRANSACTION_RESULT_EXTRA, transactionResult)

            return transactionResultIntent
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        transactionResult = intent.getSerializableExtra(TRANSACTION_RESULT_EXTRA) as TransactionResult

        setContentView(R.layout.activity_successful_transaction)

        view_on_explorer.setOnClickListener { viewOnExplorer(transactionResult.transactionHash) }
        transaction_close_btn.setOnClickListener {
            if (getSessionPin() !== null) {
                startActivity(WalletActivity.createIntent(this))
            } else {
                startActivity(LockScreenActivity.createIntent(this))
            }
        }

        val transactionResultViewBinder = TransactionResultViewBinder(transaction_result_container)
        transactionResultViewBinder.bind(transactionResult)

        check_icon.postDelayed({
            check_icon.visibility = View.VISIBLE
            (check_icon.drawable as Animatable).start()
        }, 400)
    }

    private fun viewOnExplorer(txHash: String) {
        WalletUtils.viewOnBlockExplorer(this, transactionResult.purpose, txHash)
    }

}