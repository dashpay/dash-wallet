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

package de.schildbach.wallet.ui.main

import android.app.Activity
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.AppBarLayout.Behavior.DragCallback
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.data.PaymentIntent
import de.schildbach.wallet.ui.*
import de.schildbach.wallet.ui.InputParser.StringInputParser
import de.schildbach.wallet.ui.dashpay.BottomNavFragment
import de.schildbach.wallet.ui.explore.ExploreActivity
import de.schildbach.wallet.ui.scan.ScanActivity
import de.schildbach.wallet.ui.send.SendCoinsInternalActivity
import de.schildbach.wallet.ui.send.SweepWalletActivity
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.home_content.*
import kotlinx.android.synthetic.main.sync_status_pane.*
import de.schildbach.wallet_test.databinding.HomeContentBinding
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import org.bitcoinj.core.Coin
import org.bitcoinj.core.PrefixedChecksummedBytes
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.VerificationException
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.viewBinding
import org.slf4j.LoggerFactory

@FlowPreview
@AndroidEntryPoint
@ExperimentalCoroutinesApi
class WalletFragment : BottomNavFragment(R.layout.home_content) {

    companion object {
        private val log = LoggerFactory.getLogger(WalletFragment::class.java)
    }

    private val viewModel: MainViewModel by activityViewModels()
    private val binding by viewBinding(HomeContentBinding::bind)

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val intent = result.data

        if (result.resultCode == Activity.RESULT_OK && intent != null) {
            val input = intent.getStringExtra(ScanActivity.INTENT_EXTRA_RESULT)
            input?.let { handleString(input, R.string.button_scan, R.string.input_parser_cannot_classify) }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initShortcutActions()

        val params = binding.appBar.layoutParams as CoordinatorLayout.LayoutParams

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

        viewModel.transactions.observe(viewLifecycleOwner) { refreshShortcutBar() }
        viewModel.isBlockchainSynced.observe(viewLifecycleOwner) { updateSyncState() }
        viewModel.isBlockchainSyncFailed.observe(viewLifecycleOwner) { updateSyncState() }
    }

    override fun onResume() {
        super.onResume()
        showHideSecureAction()
    }

    private fun initShortcutActions() {
        binding.shortcutsPane.setOnShortcutClickListener { v ->
            when (v) {
                binding.shortcutsPane.secureNowButton -> {
                    viewModel.logEvent(AnalyticsConstants.Home.SHORTCUT_SECURE_WALLET)
                    handleVerifySeed()
                }
                binding.shortcutsPane.scanToPayButton -> {
                    viewModel.logEvent(AnalyticsConstants.Home.SHORTCUT_SCAN_TO_PAY)
                    handleScan(v)
                }
                binding.shortcutsPane.buySellButton -> {
                    viewModel.logEvent(AnalyticsConstants.Home.SHORTCUT_BUY_AND_SELL)
                    startActivity(BuyAndSellLiquidUpholdActivity.createIntent(requireContext()))
                }
                binding.shortcutsPane.payToAddressButton -> {
                    handlePayToAddress()
                }
                binding.shortcutsPane.payToContactButton -> {
                    handleSelectContact()
                }
                binding.shortcutsPane.receiveButton -> {
                    viewModel.logEvent(AnalyticsConstants.Home.SHORTCUT_RECEIVE)
//                    startActivity(PaymentsActivity.createIntent(requireContext(), PaymentsActivity.ACTIVE_TAB_RECEIVE))
                    (requireActivity() as OnSelectPaymentTabListener).onSelectPaymentTab(
                        PaymentsFragment.ACTIVE_TAB_RECEIVE
                    )
                }
                binding.shortcutsPane.importPrivateKey -> {
                    SweepWalletActivity.start(requireContext(), true)
                }
                binding.shortcutsPane.explore -> {
//                    startActivity(Intent(requireContext(), ExploreActivity::class.java))
                    (requireActivity() as OnSelectPaymentTabListener).onSelectExploreTab()
                }
            }
        }

        refreshShortcutBar()
    }

    private fun joinDashPay() {
        startActivity(Intent(requireActivity(), CreateUsernameActivity::class.java))
    }

    private fun refreshShortcutBar() {
        showHideSecureAction()
        refreshIfUserHasBalance()
    }

    private fun showHideSecureAction() {
        binding.shortcutsPane.isPassphraseVerified = viewModel.isPassphraseVerified
    }

    private fun refreshIfUserHasBalance() {
        val balance: Coin = viewModel.balance.value ?: Coin.ZERO
        binding.shortcutsPane.userHasBalance = balance.isPositive
    }

    private fun updateSyncState() {
        val isSyncFailed = viewModel.isBlockchainSyncFailed.value

        if (isSyncFailed != null && isSyncFailed) {
            binding.syncStatusPane.syncErrorPane.isVisible = true
            return
        }

        binding.syncStatusPane.syncErrorPane.isVisible = false
        val isSynced = viewModel.isBlockchainSynced.value

        if (isSynced != null && isSynced) {
            refreshShortcutBar()
        }
    }

    private fun handleVerifySeed() {
        val checkPinSharedModel = ViewModelProvider(requireActivity())[CheckPinSharedModel::class.java]
        checkPinSharedModel.onCorrectPinCallback.observe(viewLifecycleOwner) { data ->
            if (data?.second != null) {
                startVerifySeedActivity(data.second)
            }
        }
        CheckPinDialog.show(requireActivity(), 0)
    }

    private fun handleScan(clickView: View?) {
        if (clickView != null) {
            val options = ScanActivity.getLaunchOptions(activity, clickView)
            val intent = ScanActivity.getTransitionIntent(activity, clickView)
            scanLauncher.launch(intent, options)
        } else {
            val intent = ScanActivity.getIntent(activity)
            scanLauncher.launch(intent)
        }
    }

    private fun startVerifySeedActivity(pin: String) {
        val intent: Intent = VerifySeedActivity.createIntent(requireContext(), pin)
        startActivity(intent)
    }

    private fun handleSelectContact() {
        if (requireActivity() is PaymentsPayFragment.OnSelectContactToPayListener) {
            viewModel.logEvent(AnalyticsConstants.UsersContacts.SHORTCUT_SEND_TO_CONTACT)
            (requireActivity() as PaymentsPayFragment.OnSelectContactToPayListener).selectContactToPay()
        }
    }

    private fun handlePayToAddress() {
        viewModel.logEvent(AnalyticsConstants.Home.SHORTCUT_SEND_TO_ADDRESS)
        val input = viewModel.getClipboardInput()
        handlePaste(input)
    }

    fun handlePaste(input: String) {
        if (input.isNotEmpty()) {
            handleString(
                input,
                R.string.scan_to_pay_error_dialog_title,
                R.string.scan_to_pay_error_dialog_message
            )
        } else {
            AdaptiveDialog.create(
                R.drawable.ic_info_red,
                getString(R.string.shortcut_pay_to_address),
                getString(R.string.scan_to_pay_error_dialog_message_no_data),
                getString(R.string.button_close),
                null
            ).show(requireActivity())
        }
    }

    private fun handleString(input: String, errorDialogTitleResId: Int, cannotClassifyCustomMessageResId: Int) {
        object : StringInputParser(input, true) {
            override fun handlePaymentIntent(paymentIntent: PaymentIntent) {
                if (paymentIntent.shouldConfirmAddress) {
                    AdaptiveDialog.create(
                        null,
                        getString(R.string.pay_to_confirm_address),
                        paymentIntent.address.toBase58(),
                        getString(R.string.button_cancel),
                        getString(R.string.confirm)
                    ).show(requireActivity()) { confirmed ->
                        if (confirmed != null && confirmed) {
                            SendCoinsInternalActivity.start(requireContext(), paymentIntent, true)
                        }
                    }
                } else {
                    SendCoinsInternalActivity.start(requireActivity(), paymentIntent, true)
                }
            }

            override fun handlePrivateKey(key: PrefixedChecksummedBytes) {
                SweepWalletActivity.start(requireContext(), key, true)
            }

            @Throws(VerificationException::class)
            override fun handleDirectTransaction(tx: Transaction) {
                viewModel.processDirectTransaction(tx)
            }

            override fun error(x: Exception?, messageResId: Int, vararg messageArgs: Any) {
                val dialog = AdaptiveDialog.create(
                    R.drawable.ic_info_red,
                    getString(errorDialogTitleResId),
                    if (messageArgs.isNotEmpty()) {
                        getString(messageResId, messageArgs)
                    } else {
                        getString(messageResId)
                    },
                    getString(R.string.button_close),
                    null
                )
                dialog.isMessageSelectable = true
                dialog.show(requireActivity())
            }

            override fun cannotClassify(input: String) {
                log.info("cannot classify: '{}'", input)
                error(null, cannotClassifyCustomMessageResId, input)
            }
        }.parse()
    }

    interface OnSelectPaymentTabListener {
        fun onSelectPaymentTab(mode: Int)
        fun onSelectExploreTab()
    }
}
