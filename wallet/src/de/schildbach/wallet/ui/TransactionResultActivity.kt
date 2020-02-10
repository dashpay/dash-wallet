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
import androidx.core.content.ContextCompat
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.util.WalletUtils
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_successful_transaction.*
import kotlinx.android.synthetic.main.transaction_result_content.*
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.slf4j.LoggerFactory

/**
 * @author Samuel Barbosa
 */
class TransactionResultActivity : AbstractWalletActivity() {

    private val log = LoggerFactory.getLogger(javaClass.simpleName)

    companion object {
        const val TX_ID = "tx_id"
        const val USER_AUTHORIZED_RESULT_EXTRA = "user_authorized_result_extra"

        @JvmStatic
        fun createIntent(context: Context, transaction: Transaction, userAuthorized: Boolean): Intent {
            val transactionResultIntent = Intent(context, TransactionResultActivity::class.java)
            transactionResultIntent.putExtra(TX_ID, transaction.txId)
            transactionResultIntent.putExtra(USER_AUTHORIZED_RESULT_EXTRA, userAuthorized)
            return transactionResultIntent
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val txId = intent.getSerializableExtra(TX_ID) as Sha256Hash
        setContentView(R.layout.activity_successful_transaction)

        val transactionResultViewBinder = TransactionResultViewBinder(container)
        val tx = WalletApplication.getInstance().wallet.getTransaction(txId)
        if (tx != null) {
            transactionResultViewBinder.bind(tx)
            view_on_explorer.setOnClickListener { viewOnExplorer(tx) }
            transaction_close_btn.setOnClickListener {
                if (intent.getBooleanExtra(USER_AUTHORIZED_RESULT_EXTRA, false)) {
                    startActivity(WalletActivity.createIntent(this))
                } else {
                    startActivity(LockScreenActivity.createIntentAsNewTask(this))
                }
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

}