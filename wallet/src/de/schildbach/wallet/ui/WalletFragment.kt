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

import android.app.Activity
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProviders
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.AppBarLayout.Behavior.DragCallback
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.BlockchainIdentityData
import de.schildbach.wallet.data.BlockchainState
import de.schildbach.wallet.data.PaymentIntent
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.CheckPinDialog.Companion.show
import de.schildbach.wallet.ui.InputParser.StringInputParser
import de.schildbach.wallet.ui.MainActivity.Companion.REQUEST_CODE_SCAN
import de.schildbach.wallet.ui.PaymentsFragment.Companion.ACTIVE_TAB_PAY
import de.schildbach.wallet.ui.PaymentsFragment.Companion.ACTIVE_TAB_RECEIVE
import de.schildbach.wallet.ui.VerifySeedActivity.Companion.createIntent
import de.schildbach.wallet.ui.dashpay.CreateIdentityService.Companion.createIntentForRetry
import de.schildbach.wallet.ui.dashpay.DashPayViewModel
import de.schildbach.wallet.ui.scan.ScanActivity
import de.schildbach.wallet.ui.send.SendCoinsInternalActivity
import de.schildbach.wallet.ui.send.SweepWalletActivity
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.home_content.*
import kotlinx.android.synthetic.main.quick_actions_layout.*
import kotlinx.android.synthetic.main.sync_status_pane.*
import org.bitcoinj.core.Coin
import org.bitcoinj.core.PrefixedChecksummedBytes
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.VerificationException
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.listeners.WalletCoinsReceivedEventListener
import org.bitcoinj.wallet.listeners.WalletCoinsSentEventListener
import org.dash.wallet.integration.uphold.ui.UpholdAccountActivity

class WalletFragment : Fragment() {

    private var clipboardManager: ClipboardManager? = null
    private var blockchainState: BlockchainState? = null
    private var syncComplete = false
    private var dashPayViewModel: DashPayViewModel? = null
    private var isPlatformAvailable = false
    private var noIdentityCreatedOrInProgress = true
    private var retryCreationIfInProgress = true

    private val walletApplication by lazy { WalletApplication.getInstance() }
    private val wallet by lazy { walletApplication.wallet }
    private val config by lazy { walletApplication.configuration }
    private val coinsSendReceivedListener = OnCoinsSentReceivedListener()
    private var walletFragmentView: View? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        if (walletFragmentView == null) {
            walletFragmentView = inflater.inflate(R.layout.home_content, container, false)
        }
        return walletFragmentView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        initViewModel()
        clipboardManager = activity?.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?

        val appBar: View = app_bar
        val params = appBar.layoutParams as CoordinatorLayout.LayoutParams
        if (params.behavior == null) {
            params.behavior = AppBarLayout.Behavior()
        }
        val behaviour = params.behavior as AppBarLayout.Behavior?
        behaviour!!.setDragCallback(object : DragCallback() {
            override fun canDrag(appBarLayout: AppBarLayout): Boolean {
                //TODO: WalletFragment and WalletTransactionsFragment can probably be merged.
                val walletTransactionsFragment = childFragmentManager
                        .findFragmentById(R.id.wallet_transactions_fragment) as WalletTransactionsFragment?
                return walletTransactionsFragment != null && !walletTransactionsFragment.isHistoryEmpty
            }
        })

        AppDatabase.getAppDatabase().blockchainStateDao().load().observe(viewLifecycleOwner, Observer<BlockchainState?> { t ->
            blockchainState = t
            updateSyncState()
            showHideJoinDashPayAction()
        })
        registerOnCoinsSentReceivedListener()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        unregisterWalletListener()
    }

    override fun onResume() {
        super.onResume()
        showHideSecureAction()
        showHideJoinDashPayAction()
    }

    private fun registerOnCoinsSentReceivedListener() {
        val mainThreadExecutor = ContextCompat.getMainExecutor(requireContext())
        wallet.addCoinsReceivedEventListener(mainThreadExecutor, coinsSendReceivedListener)
        wallet.addCoinsSentEventListener(mainThreadExecutor, coinsSendReceivedListener)
    }

    private fun unregisterWalletListener() {
        wallet.removeCoinsReceivedEventListener(coinsSendReceivedListener)
        wallet.removeCoinsSentEventListener(coinsSendReceivedListener)
    }

    private fun updateSyncPaneVisibility(id: Int, visible: Boolean) {
        view?.findViewById<View>(id)?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun updateSyncState() {
        if (blockchainState == null) {
            return
        }
        var percentage: Int = blockchainState!!.percentageSync
        if (blockchainState!!.replaying && blockchainState!!.percentageSync == 100) {
            //This is to prevent showing 100% when using the Rescan blockchain function.
            //The first few broadcasted blockchainStates are with percentage sync at 100%
            percentage = 0
        }
        val syncProgressView = sync_status_progress
        if (blockchainState != null && blockchainState!!.syncFailed()) {
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
        syncComplete = blockchainState!!.isSynced()
        if (syncComplete) {
            syncPercentageView.setTextColor(resources.getColor(R.color.success_green))
            syncStatusTitle.setText(R.string.sync_status_sync_title)
            syncStatusMessage.setText(R.string.sync_status_sync_completed)
            updateSyncPaneVisibility(R.id.sync_status_pane, false)
        } else {
            syncPercentageView.setTextColor(resources.getColor(R.color.dash_gray))
            updateSyncPaneVisibility(R.id.sync_status_pane, true)
            syncStatusTitle.setText(R.string.sync_status_syncing_title)
            syncStatusMessage.setText(R.string.sync_status_syncing_sub_title)
        }
    }

    private fun initView() {
        initQuickActions()
        if (requireActivity() is OnSelectPaymentTabListener) {
            pay_btn.setOnClickListener(View.OnClickListener {
                (requireActivity() as OnSelectPaymentTabListener).onSelectPaymentTab(ACTIVE_TAB_PAY)
            })
            receive_btn.setOnClickListener {
                (requireActivity() as OnSelectPaymentTabListener).onSelectPaymentTab(ACTIVE_TAB_RECEIVE)
            }
        }

    }

    private fun initViewModel() {
        //
        // Currently this is only used to check the status of Platform before showing
        // the Join DashPay (evolution) button on the shortcuts bar.
        // If that is the only function that the platform required for, then we can
        // conditionally execute this code when a username hasn't been registered.
        dashPayViewModel = ViewModelProvider(this)[DashPayViewModel::class.java]
        dashPayViewModel!!.isPlatformAvailableLiveData.observe(viewLifecycleOwner, object : Observer<Resource<Boolean?>?> {
            override fun onChanged(status: Resource<Boolean?>?) {
                if (status?.status === Status.SUCCESS) {
                    if (status.data != null) {
                        isPlatformAvailable = status.data
                    }
                } else {
                    isPlatformAvailable = false
                }
                showHideJoinDashPayAction()
            }
        })
        AppDatabase.getAppDatabase().blockchainIdentityDataDao().loadBase().observe(viewLifecycleOwner,
                Observer {
                    if (it != null) {
                        noIdentityCreatedOrInProgress = it.creationState == BlockchainIdentityData.CreationState.NONE
                        showHideJoinDashPayAction()
                        if (retryCreationIfInProgress && it.creationInProgress) {
                            retryCreationIfInProgress = false
                            activity?.startService(createIntentForRetry(requireActivity(), false))
                        }
                    }

                }
        )
    }

    fun showHideJoinDashPayAction() {
        if (noIdentityCreatedOrInProgress && syncComplete && isPlatformAvailable) {
            val walletBalance: Coin = wallet.getBalance(Wallet.BalanceType.ESTIMATED)
            val canAffordIt = (walletBalance.isGreaterThan(Constants.DASH_PAY_FEE)
                    || walletBalance == Constants.DASH_PAY_FEE)
            val visible = canAffordIt && config.showJoinDashPay
            join_dashpay_action.visibility = if (visible) View.VISIBLE else View.GONE
        } else {
            join_dashpay_action.visibility = View.GONE
        }
        join_dashpay_action_space.visibility = join_dashpay_action.visibility
    }

    private fun showHideSecureAction() {
        secure_action.visibility = if (config.remindBackupSeed) View.VISIBLE else View.GONE
        secure_action_space.visibility = secure_action.visibility
    }

    private fun handleVerifySeed() {
        val checkPinSharedModel = ViewModelProviders.of(this)[CheckPinSharedModel::class.java]
        checkPinSharedModel.onCorrectPinCallback.observe(viewLifecycleOwner, Observer<Pair<Int?, String?>?> { data ->
            if (data?.second != null) {
                startVerifySeedActivity(data.second!!)
            }
        })
        show(requireActivity(), 0)
    }

    private fun handleScan(clickView: View?) {
        ScanActivity.startForResult(requireActivity(), clickView, MainActivity.REQUEST_CODE_SCAN)
    }

    private fun startVerifySeedActivity(pin: String) {
        val intent: Intent = createIntent(requireContext(), pin)
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

    private fun initQuickActions() {
        showHideSecureAction()
        secure_action.setOnClickListener { handleVerifySeed() }
        join_dashpay_action.setOnClickListener {
            startActivity(Intent(requireActivity(), CreateUsernameActivity::class.java))
        }
        showHideJoinDashPayAction()
        scan_to_pay_action.setOnClickListener(View.OnClickListener { v -> handleScan(v) })
        buy_sell_action.setOnClickListener {
            startActivity(UpholdAccountActivity.createIntent(requireContext(), wallet))
        }
        pay_to_address_action.setOnClickListener(View.OnClickListener { handlePaste() })
        import_key_action.setOnClickListener {
            SweepWalletActivity.start(requireContext(), true)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)
        if (requestCode == REQUEST_CODE_SCAN && resultCode == Activity.RESULT_OK) {
            if (intent != null) {
                val input = intent.getStringExtra(ScanActivity.INTENT_EXTRA_RESULT)
                handleString(input, R.string.button_scan, R.string.input_parser_cannot_classify)
            }
        } else {
            super.onActivityResult(requestCode, resultCode, intent)
        }
    }

    inner class OnCoinsSentReceivedListener : WalletCoinsReceivedEventListener, WalletCoinsSentEventListener {
        override fun onCoinsReceived(wallet: Wallet, tx: Transaction, prevBalance: Coin, newBalance: Coin) {
            onCoinsSentReceived()
        }

        override fun onCoinsSent(wallet: Wallet, tx: Transaction, prevBalance: Coin, newBalance: Coin) {
            onCoinsSentReceived()
        }

        private fun onCoinsSentReceived() {
            showHideJoinDashPayAction()
        }
    }

    interface OnSelectPaymentTabListener {
        fun onSelectPaymentTab(mode: Int)
    }
}