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
import de.schildbach.wallet.ui.send.SendCoinsInternalActivity.ACTION_SEND_FROM_WALLET_URI
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

        val transactionResultViewBinder = TransactionResultViewBinder(container)
        val tx = WalletApplication.getInstance().wallet.getTransaction(txId)
        if (tx != null) {
            val payeeName = intent.getStringExtra(EXTRA_PAYMENT_MEMO)
            val payeeVerifiedBy = intent.getStringExtra(EXTRA_PAYEE_VERIFIED_BY)
            transactionResultViewBinder.bind(tx, payeeName, payeeVerifiedBy)
            view_on_explorer.setOnClickListener { viewOnExplorer(tx) }
            transaction_close_btn.setOnClickListener {
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