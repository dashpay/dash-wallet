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
import android.os.Bundle
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import de.schildbach.wallet.ui.main.MainActivity

import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.ui.DashPayUserActivity
import de.schildbach.wallet.ui.dashpay.transactions.PrivateMemoDialog
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.ui.LockScreenActivity
import de.schildbach.wallet.ui.ReportIssueDialogBuilder
import de.schildbach.wallet.ui.TransactionResultViewModel
import de.schildbach.wallet.ui.send.SendCoinsActivity
import de.schildbach.wallet.util.WalletUtils
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.ActivitySuccessfulTransactionBinding
import de.schildbach.wallet_test.databinding.TransactionResultContentBinding
import kotlinx.coroutines.flow.filterNotNull
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.util.observe
import org.slf4j.LoggerFactory
import javax.inject.Inject

/**
 * @author Samuel Barbosa
 */
@AndroidEntryPoint
class TransactionResultActivity : LockScreenActivity() {
    private val log = LoggerFactory.getLogger(javaClass.simpleName)

    companion object {
        private const val EXTRA_TX_ID = "tx_id"
        private const val EXTRA_USER_AUTHORIZED_RESULT_EXTRA = "user_authorized_result_extra"
        private const val EXTRA_USER_AUTHORIZED = "user_authorized"
        private const val EXTRA_PAYMENT_MEMO = "payee_name"
        private const val EXTRA_PAYEE_VERIFIED_BY = "payee_verified_by"
        private const val EXTRA_USER_DATA = "user_data"

        @JvmStatic
        fun createIntent(
            context: Context,
            action: String? = null,
            transaction: Transaction,
            userAuthorized: Boolean
        ): Intent {
            return createIntent(context, action, transaction, userAuthorized, null, null)
        }

        @JvmStatic
        fun createIntent(
            context: Context,
            transaction: Transaction,
            userAuthorized: Boolean,
            payeeName: String? = null,
            payeeVerifiedBy: String? = null
        ): Intent {
            return createIntent(context, null, transaction, userAuthorized, payeeName, payeeVerifiedBy)
        }

        fun createIntent(
            context: Context,
            action: String?,
            transaction: Transaction,
            userAuthorized: Boolean,
            paymentMemo: String? = null,
            payeeVerifiedBy: String? = null
        ): Intent {
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

    private val isUserAuthorised: Boolean by lazy {
        intent.extras!!.getBoolean(EXTRA_USER_AUTHORIZED)
    }

    private val userData by lazy {
        intent.extras!!.getParcelable<UsernameSearchResult>(EXTRA_USER_DATA)
    }

    private val viewModel: TransactionResultViewModel by viewModels()
    private lateinit var binding: ActivitySuccessfulTransactionBinding
    private lateinit var contentBinding: TransactionResultContentBinding
    @Inject
    lateinit var dashPayProfileDao: DashPayProfileDao

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val txId = intent.getSerializableExtra(EXTRA_TX_ID) as Sha256Hash
        if (intent.extras?.getBoolean(EXTRA_USER_AUTHORIZED_RESULT_EXTRA, false)!!) {
            intent.putExtra(INTENT_EXTRA_KEEP_UNLOCKED, true)
        }

        binding = ActivitySuccessfulTransactionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        contentBinding = TransactionResultContentBinding.bind(binding.container)
        transactionResultViewBinder = TransactionResultViewBinder(
            walletData.wallet!!,
            configuration.format.noCode(),
            contentBinding
        )

        viewModel.init(txId)

        viewModel.transaction.filterNotNull().observe(this) { tx ->
            transactionResultViewBinder.setTransactionIcon(R.drawable.check_animated)
            contentBinding.openExplorerCard.setOnClickListener { viewOnExplorer(tx) }
            contentBinding.taxCategoryLayout.setOnClickListener { viewOnTaxCategory() }
            binding.transactionCloseBtn.setOnClickListener {
                onTransactionDetailsDismiss()
            }
            contentBinding.reportIssueCard.setOnClickListener {
                showReportIssue()
            }

            viewModel.transactionMetadata.observe(this) {
                transactionResultViewBinder.setTransactionMetadata(it)
            }
            transactionResultViewBinder.setOnRescanTriggered { rescanBlockchain() }


            viewModel.transactionMetadata.observe(this) {
                transactionResultViewBinder.setTransactionMetadata(it)
            }

            viewModel.contact.observe(this) { profile ->
                finishInitialization(tx, profile)
            }
        }
    }

    private fun finishInitialization(tx: Transaction, dashPayProfile: DashPayProfile?) {
        initiateTransactionBinder(tx, dashPayProfile)
        val mainThreadExecutor = ContextCompat.getMainExecutor(walletApplication)
        tx.confidence.addEventListener(mainThreadExecutor, transactionResultViewBinder)
    }

    private fun viewOnExplorer(tx: Transaction) {
        WalletUtils.viewOnBlockExplorer(this, tx.purpose, tx.txId.toString())
    }

    private fun initiateTransactionBinder(tx: Transaction, dashPayProfile: DashPayProfile?) {
        val payeeName = intent.getStringExtra(EXTRA_PAYMENT_MEMO)
        val payeeVerifiedBy = intent.getStringExtra(EXTRA_PAYEE_VERIFIED_BY)
        transactionResultViewBinder.bind(tx, dashPayProfile, payeeName, payeeVerifiedBy)
        binding.transactionCloseBtn.setOnClickListener {
            onTransactionDetailsDismiss()
        }
        contentBinding.reportIssueCard.setOnClickListener { showReportIssue() }
        contentBinding.taxCategoryLayout.setOnClickListener { viewOnTaxCategory()}
        contentBinding.openExplorerCard.setOnClickListener { viewOnExplorer(tx) }
        contentBinding.addPrivateMemoBtn.setOnClickListener {
            viewModel.transaction?.value?.txId?.let { hash ->
                PrivateMemoDialog().apply {
                    arguments = bundleOf(PrivateMemoDialog.TX_ID_ARG to hash)
                }.show(supportFragmentManager, "private_memo")
            }
        }

        transactionResultViewBinder.setTransactionIcon(R.drawable.check_animated)
        transactionResultViewBinder.setOnRescanTriggered { rescanBlockchain() }
    }

    private fun viewOnTaxCategory() {
        // this should eventually trigger the observer to update the view
        viewModel.toggleTaxCategory()
    }

    private fun showReportIssue() {
        ReportIssueDialogBuilder.createReportIssueDialog(
            this,
            packageInfoProvider,
            configuration,
            viewModel.walletData.wallet,
            walletApplication
        ).buildAlertDialog().show()
    }

    private fun onTransactionDetailsDismiss() {
        if (isFinishing || isDestroyed) {
            log.warn("Activity is finishing or destroyed. Skipping dismiss actions.")
            return
        }

        when {
            intent.action == Intent.ACTION_VIEW || intent.action == SendCoinsActivity.ACTION_SEND_FROM_WALLET_URI -> {
                finish()
            }
            userData != null -> {
                finish()
                startActivity(
                    DashPayUserActivity.createIntent(this@TransactionResultActivity,
                    userData!!, userData != null))
            }
            intent.getBooleanExtra(EXTRA_USER_AUTHORIZED_RESULT_EXTRA, false) -> {
                startActivity(MainActivity.createIntent(this))
            }
            else -> {
                startActivity(MainActivity.createIntent(this))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.transaction.value?.confidence?.removeEventListener(transactionResultViewBinder)
    }

    private fun rescanBlockchain() {
        AdaptiveDialog.create(
            null,
            getString(R.string.preferences_initiate_reset_title),
            getString(R.string.preferences_initiate_reset_dialog_message),
            getString(R.string.button_cancel),
            getString(R.string.preferences_initiate_reset_dialog_positive)
        ).show(this) {
            if (it == true) {
                log.info("manually initiated blockchain reset")
                viewModel.rescanBlockchain()
                finish()
            } else {
                viewModel.logEvent(AnalyticsConstants.Settings.RESCAN_BLOCKCHAIN_DISMISS)
            }
        }
    }
}
