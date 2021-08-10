/*
 * Copyright 2020 Dash Core Group.
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

package de.schildbach.wallet.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.AppBarLayout.Behavior.DragCallback
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.BlockchainState
import de.schildbach.wallet.data.PaymentIntent
import de.schildbach.wallet.ui.InputParser.StringInputParser
import de.schildbach.wallet.ui.dashpay.BottomNavFragment
import de.schildbach.wallet.ui.scan.ScanActivity
import de.schildbach.wallet.ui.send.SendCoinsInternalActivity
import de.schildbach.wallet.ui.send.SweepWalletActivity
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.home_content.*
import kotlinx.android.synthetic.main.sync_status_pane.*
import org.bitcoinj.core.PrefixedChecksummedBytes
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.VerificationException
import org.dash.wallet.integration.uphold.ui.UpholdAccountActivity

class WalletFragment : BottomNavFragment(R.layout.home_content) {

    companion object {
        private const val REQUEST_CODE_SCAN = 0
    }

    override val navigationItemId = R.id.bottom_home

    private lateinit var mainActivityViewModel: MainActivityViewModel

    private var clipboardManager: ClipboardManager? = null
    private var syncComplete = false

    private val walletApplication by lazy { WalletApplication.getInstance() }
    private val wallet by lazy { walletApplication.wallet }
    private val config by lazy { walletApplication.configuration }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        clipboardManager = activity?.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?

        val appBar: View = app_bar
        val params = appBar.layoutParams as CoordinatorLayout.LayoutParams
        if (params.behavior == null) {
            params.behavior = AppBarLayout.Behavior()
        }
        val behaviour = params.behavior as AppBarLayout.Behavior?
        behaviour!!.setDragCallback(object : DragCallback() {
            override fun canDrag(appBarLayout: AppBarLayout): Boolean {
                val walletTransactionsFragment = childFragmentManager
                        .findFragmentByTag("wallet_transactions_fragment") as WalletTransactionsFragment
                return !walletTransactionsFragment.isHistoryEmpty
            }
        })
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        initViewModel()
        initShortcutActions()
    }

    fun initViewModel() {
        mainActivityViewModel = ViewModelProvider(requireActivity())[MainActivityViewModel::class.java]
        mainActivityViewModel.blockchainStateData.observe(viewLifecycleOwner, Observer {
            if (it != null) {
                updateSyncState(it)
            }
        })
    }

    override fun onResume() {
        super.onResume()
        showHideSecureAction()
    }

    private fun initShortcutActions() {
        shortcuts_pane!!.setOnShortcutClickListener(View.OnClickListener { v ->
            when (v) {
                shortcuts_pane.secureNowButton -> {
                    handleVerifySeed()
                }
                shortcuts_pane.scanToPayButton -> {
                    handleScan(v)
                }
                shortcuts_pane.buySellButton -> {
                    startActivity(UpholdAccountActivity.createIntent(requireContext()))
                }
                shortcuts_pane.payToAddressButton -> {
                    handlePaste()
                }
                shortcuts_pane.payToContactButton -> {
                    handleSelectContact()
                }
                shortcuts_pane.receiveButton -> {
                    (requireActivity() as OnSelectPaymentTabListener).onSelectPaymentTab(PaymentsFragment.ACTIVE_TAB_RECEIVE)
                }
                shortcuts_pane.importPrivateKey -> {
                    SweepWalletActivity.start(requireContext(), true)
                }
            }
        })
    }

    private fun joinDashPay() {
        startActivity(Intent(requireActivity(), CreateUsernameActivity::class.java))
    }

    private fun showHideSecureAction() {
        shortcuts_pane.showSecureNow(config.remindBackupSeed)
    }

    private fun updateSyncPaneVisibility(id: Int, visible: Boolean) {
        view?.findViewById<View>(id)?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    @SuppressLint("SetTextI18n")
    private fun updateSyncState(blockchainState: BlockchainState) {
        var percentage: Int = blockchainState.percentageSync
        if (blockchainState.replaying && blockchainState.percentageSync == 100) {
            //This is to prevent showing 100% when using the Rescan blockchain function.
            //The first few broadcasted blockchainStates are with percentage sync at 100%
            percentage = 0
        }
        val syncProgressView = sync_status_progress
        if (blockchainState.syncFailed()) {
            updateSyncPaneVisibility(R.id.sync_status_pane, true)
            sync_progress_pane.visibility = View.GONE
            sync_error_pane.visibility = View.VISIBLE
            return
        }
        updateSyncPaneVisibility(R.id.sync_error_pane, false)
        updateSyncPaneVisibility(R.id.sync_progress_pane, true)
        val syncStatusTitle = sync_status_title
        val syncStatusMessage = sync_status_message
        syncProgressView.progress = percentage
        val syncPercentageView = sync_status_percentage
        syncPercentageView.text = "$percentage%"
        syncComplete = blockchainState.isSynced()
        if (syncComplete) {
            syncPercentageView.setTextColor(ContextCompat.getColor(requireContext(), R.color.success_green))
            syncStatusTitle.setText(R.string.sync_status_sync_title)
            syncStatusMessage.setText(R.string.sync_status_sync_completed)
            updateSyncPaneVisibility(R.id.sync_status_pane, false)
        } else {
            syncPercentageView.setTextColor(ContextCompat.getColor(requireContext(), R.color.dash_gray))
            updateSyncPaneVisibility(R.id.sync_status_pane, true)
            syncStatusTitle.setText(R.string.sync_status_syncing_title)
            syncStatusMessage.setText(R.string.sync_status_syncing_sub_title)
        }
    }

    private fun handleVerifySeed() {
        val checkPinSharedModel = ViewModelProvider(requireActivity())[CheckPinSharedModel::class.java]
        checkPinSharedModel.onCorrectPinCallback.observe(viewLifecycleOwner, Observer<Pair<Int?, String?>?> { data ->
            if (data?.second != null) {
                startVerifySeedActivity(data.second!!)
            }
        })
        CheckPinDialog.show(requireActivity(), 0)
    }

    private fun handleScan(clickView: View?) {
        ScanActivity.startForResult(requireActivity(), clickView, REQUEST_CODE_SCAN)
    }

    private fun handleSelectContact() {
        if (requireActivity() is PaymentsPayFragment.OnSelectContactToPayListener) {
            (requireActivity() as PaymentsPayFragment.OnSelectContactToPayListener).selectContactToPay()
        }
    }

    private fun startVerifySeedActivity(pin: String) {
        val intent: Intent = VerifySeedActivity.createIntent(requireContext(), pin)
        startActivity(intent)
    }

    private fun handlePaste() {
        var input: String? = null
        if (clipboardManager!!.hasPrimaryClip()) {
            val clip = clipboardManager!!.primaryClip ?: return
            val clipDescription = clip.description
            if (clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST)) {
                val clipUri = clip.getItemAt(0).uri
                if (clipUri != null) {
                    input = clipUri.toString()
                }
            } else if (clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)
                    || clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)) {
                val clipText = clip.getItemAt(0).text
                if (clipText != null) {
                    input = clipText.toString()
                }
            }
        }
        if (input != null) {
            handleString(input, R.string.scan_to_pay_error_dialog_title, R.string.scan_to_pay_error_dialog_message)
        } else {
            InputParser.dialog(requireContext(), null, R.string.scan_to_pay_error_dialog_title, R.string.scan_to_pay_error_dialog_message_no_data)
        }
    }

    private fun handleString(input: String, errorDialogTitleResId: Int, cannotClassifyCustomMessageResId: Int) {
        object : StringInputParser(input, true) {
            override fun handlePaymentIntent(paymentIntent: PaymentIntent) {
                SendCoinsInternalActivity.start(requireContext(), paymentIntent, true)
            }

            override fun handlePrivateKey(key: PrefixedChecksummedBytes) {
                SweepWalletActivity.start(requireContext(), key, true)
            }

            @Throws(VerificationException::class)
            override fun handleDirectTransaction(tx: Transaction) {
                walletApplication.processDirectTransaction(tx);
            }

            override fun error(x: Exception?, messageResId: Int, vararg messageArgs: Any) {
                InputParser.dialog(requireContext(), null, errorDialogTitleResId, messageResId, *messageArgs)
            }

            override fun cannotClassify(input: String) {
                AbstractWalletActivity.log.info("cannot classify: '{}'", input)
                error(null, cannotClassifyCustomMessageResId, input)
            }
        }.parse()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)
        if (requestCode == REQUEST_CODE_SCAN && resultCode == Activity.RESULT_OK) {
            if (intent != null) {
                val input = intent.getStringExtra(ScanActivity.INTENT_EXTRA_RESULT)!!
                handleString(input, R.string.button_scan, R.string.input_parser_cannot_classify)
            }
        } else {
            super.onActivityResult(requestCode, resultCode, intent)
        }
    }

    interface OnSelectPaymentTabListener {
        fun onSelectPaymentTab(mode: Int)
    }
}
