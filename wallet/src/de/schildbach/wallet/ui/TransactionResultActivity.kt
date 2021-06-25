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
import androidx.lifecycle.Observer
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.DashPayProfile
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.util.WalletUtils
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_successful_transaction.*
import kotlinx.android.synthetic.main.transaction_result_content.*
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.dashevo.dashpay.BlockchainIdentity
import org.slf4j.LoggerFactory

/**
 * @author Samuel Barbosa
 */
class TransactionResultActivity : AbstractWalletActivity() {

    private val log = LoggerFactory.getLogger(javaClass.simpleName)

    companion object {
        private const val EXTRA_TX_ID = "tx_id"
        private const val EXTRA_USER_AUTHORIZED_RESULT_EXTRA = "user_authorized_result_extra"
        private const val EXTRA_USER_AUTHORIZED = "user_authorized"
        private const val EXTRA_PAYMENT_MEMO = "payee_name"
        private const val EXTRA_PAYEE_VERIFIED_BY = "payee_verified_by"
        private const val EXTRA_USER_DATA = "user_data"

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
                putExtra(EXTRA_USER_AUTHORIZED, userAuthorized)
                putExtra(EXTRA_PAYMENT_MEMO, paymentMemo)
                putExtra(EXTRA_PAYEE_VERIFIED_BY, payeeVerifiedBy)
            }
        }

        @JvmStatic
        fun createIntent(context: Context, action: String?, transaction: Transaction, userAuthorized: Boolean,
                         userData: UsernameSearchResult?): Intent {
            return Intent(context, TransactionResultActivity::class.java).apply {
                setAction(action)
                putExtra(EXTRA_TX_ID, transaction.txId)
                putExtra(EXTRA_USER_AUTHORIZED, userAuthorized)
                putExtra(EXTRA_USER_DATA, userData)
            }
        }
    }

    private lateinit var transactionResultViewBinder: TransactionResultViewBinder
    private lateinit var transaction: Transaction

    private val isUserAuthorised: Boolean by lazy {
        intent.extras!!.getBoolean(EXTRA_USER_AUTHORIZED)
    }

    private val userData by lazy {
        intent.extras!!.getParcelable<UsernameSearchResult>(EXTRA_USER_DATA)
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val txId = intent.getSerializableExtra(EXTRA_TX_ID) as Sha256Hash
        setContentView(R.layout.activity_successful_transaction)

        val blockchainIdentity: BlockchainIdentity? = PlatformRepo.getInstance().getBlockchainIdentity()

        val tx = WalletApplication.getInstance().wallet.getTransaction(txId)
        if (tx != null) {
            val payeeName = intent.getStringExtra(EXTRA_PAYMENT_MEMO)
            val payeeVerifiedBy = intent.getStringExtra(EXTRA_PAYEE_VERIFIED_BY)
            transactionResultViewBinder.bind(tx, payeeName, payeeVerifiedBy)
            view_on_explorer.setOnClickListener { viewOnExplorer(tx) }
            transaction_close_btn.setOnClickListener {
                when {
                    intent.action == Intent.ACTION_VIEW -> {
                        finish()
                    }
                    intent.getBooleanExtra(EXTRA_USER_AUTHORIZED_RESULT_EXTRA, false) -> {
                        startActivity(MainActivity.createIntent(this))
                    }
                    else -> {
                        startActivity(MainActivity.createIntent(this))
                    }
                }
            }
        }

        var profile: DashPayProfile?
        var userId: String? = null
        if (blockchainIdentity != null) {
            userId = blockchainIdentity.getContactForTransaction(tx!!)
            if (userId != null) {
                AppDatabase.getAppDatabase().dashPayProfileDaoAsync().loadByUserIdDistinct(userId).observe(this, Observer {
                    if (it != null) {
                        profile = it
                        finishInitialization(tx, profile)
                    }
                })
            }
        }

        if (blockchainIdentity == null || userId == null)
            finishInitialization(tx!!, null)
    }

    private fun finishInitialization(tx: Transaction, dashPayProfile: DashPayProfile?) {
        this.transaction = tx
        transactionResultViewBinder = TransactionResultViewBinder(container, dashPayProfile, true)
        val payeeName = intent.getStringExtra(EXTRA_PAYMENT_MEMO)
        val payeeVerifiedBy = intent.getStringExtra(EXTRA_PAYEE_VERIFIED_BY)
        transactionResultViewBinder.bind(tx, payeeName, payeeVerifiedBy)
        val mainThreadExecutor = ContextCompat.getMainExecutor(walletApplication)
        tx.confidence.addEventListener(mainThreadExecutor, transactionResultViewBinder)
        view_on_explorer.setOnClickListener { viewOnExplorer(tx) }
        transaction_close_btn.setOnClickListener {
            when {
                intent.action == Intent.ACTION_VIEW -> {
                    super.finish()
                }
                userData != null -> {
                    finish()
                    startActivity(DashPayUserActivity.createIntent(this@TransactionResultActivity,
                            userData!!, userData != null))
                }
                else -> {
                    super.finish()
                }
            }
        }

        check_icon.postDelayed({
            check_icon.visibility = View.VISIBLE
            (check_icon.drawable as Animatable).start()
        }, 400)
    }

    private fun viewOnExplorer(tx: Transaction) {
        WalletUtils.viewOnBlockExplorer(this, tx.purpose, tx.txId.toString())
    }

}