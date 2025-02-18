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
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.AppBarLayout.Behavior.DragCallback
import com.google.android.material.transition.MaterialFadeThrough
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.data.PaymentIntent
import de.schildbach.wallet.service.CoinJoinMode
import de.schildbach.wallet.service.MixingStatus
import de.schildbach.wallet.ui.*
import de.schildbach.wallet.ui.util.InputParser.StringInputParser
import de.schildbach.wallet.ui.dashpay.ContactsScreenMode
import de.schildbach.wallet.ui.dashpay.NotificationsFragment
import de.schildbach.wallet.ui.dashpay.utils.display
import de.schildbach.wallet.ui.payments.PaymentsFragment
import de.schildbach.wallet.ui.payments.SweepWalletActivity
import de.schildbach.wallet.ui.scan.ScanActivity
import de.schildbach.wallet.ui.send.SendCoinsActivity
import de.schildbach.wallet.ui.transactions.TaxCategoryExplainerDialogFragment
import de.schildbach.wallet.ui.transactions.TransactionDetailsDialogFragment
import de.schildbach.wallet.ui.verify.VerifySeedActivity
import de.schildbach.wallet.util.WalletUtils
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.HomeContentBinding
import de.schildbach.wallet_test.databinding.MixingStatusPaneBinding
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.core.PrefixedChecksummedBytes
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.VerificationException
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.services.AuthenticationManager
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.avatar.ProfilePictureDisplay
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.observe
import org.dash.wallet.common.util.safeNavigate
import org.slf4j.LoggerFactory
import javax.inject.Inject

@AndroidEntryPoint
class WalletFragment : Fragment(R.layout.home_content) {
    companion object {
        private val log = LoggerFactory.getLogger(WalletFragment::class.java)
        private const val TRANSACTIONS_FRAGMENT_TAG = "wallet_transactions_fragment"
    }

    private val viewModel: MainViewModel by activityViewModels()
    private val binding by viewBinding(HomeContentBinding::bind)
    private lateinit var mixingBinding: MixingStatusPaneBinding
    @Inject lateinit var configuration: Configuration
    @Inject lateinit var authManager: AuthenticationManager

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

        reenterTransition = MaterialFadeThrough()
        initShortcutActions()
        mixingBinding = MixingStatusPaneBinding.bind(binding.mixingStatusPane.root)

        val params = binding.appBar.layoutParams as CoordinatorLayout.LayoutParams

        if (params.behavior == null) {
            params.behavior = AppBarLayout.Behavior()
        }
        val behaviour = params.behavior as AppBarLayout.Behavior?
        behaviour!!.setDragCallback(object : DragCallback() {
            override fun canDrag(appBarLayout: AppBarLayout): Boolean {
                val walletTransactionsFragment = childFragmentManager
                    .findFragmentByTag(TRANSACTIONS_FRAGMENT_TAG) as WalletTransactionsFragment
                return !walletTransactionsFragment.isHistoryEmpty
            }
        })

        binding.homeToolbar.setOnClickListener { scrollToTop() }
        binding.notificationBell.setOnClickListener {
            viewModel.logEvent(AnalyticsConstants.Home.NOTIFICATIONS)
            findNavController().navigate(
                R.id.showNotificationsFragment,
                bundleOf("mode" to NotificationsFragment.MODE_NOTIFICATIONS),
                NavOptions.Builder()
                    .setEnterAnim(R.anim.slide_in_right)
                    .build(),
            )
        }
        binding.dashpayUserAvatar.setOnClickListener {
            viewModel.logEvent(AnalyticsConstants.Home.AVATAR)
            startActivity(Intent(requireContext(), EditProfileActivity::class.java))
        }

        viewModel.transactions.observe(viewLifecycleOwner) { refreshShortcutBar() }
        viewModel.isBlockchainSynced.observe(viewLifecycleOwner) { updateSyncState() }
        viewModel.isBlockchainSyncFailed.observe(viewLifecycleOwner) { updateSyncState() }
        viewModel.mostRecentTransaction.observe(viewLifecycleOwner) { mostRecentTransaction: Transaction ->
            log.info("most recent transaction: {}", mostRecentTransaction.txId)

            if ((requireActivity() as? LockScreenActivity)?.lockScreenDisplayed != true &&
                !configuration.hasDisplayedTaxCategoryExplainer &&
                WalletUtils.getTransactionDate(mostRecentTransaction).time >= configuration.taxCategoryInstallTime
            ) {
                val dialogFragment: TaxCategoryExplainerDialogFragment =
                    TaxCategoryExplainerDialogFragment.newInstance(mostRecentTransaction.txId)
                dialogFragment.show(requireActivity()) {
                    val transactionDetailsDialogFragment: TransactionDetailsDialogFragment =
                        TransactionDetailsDialogFragment.newInstance(mostRecentTransaction.txId)
                    transactionDetailsDialogFragment.show(requireActivity())
                }
                configuration.setHasDisplayedTaxCategoryExplainer()
            }
        }

        viewModel.dashPayProfile.observe(viewLifecycleOwner) { profile ->
            if (viewModel.hasIdentity) {
                ProfilePictureDisplay.display(binding.dashpayUserAvatar, profile, true)
                setNotificationIndicator()
            }
        }

        viewModel.blockchainIdentity.observe(viewLifecycleOwner) { identity ->
            if (identity?.creationComplete == true) {
                ProfilePictureDisplay.display(binding.dashpayUserAvatar, viewModel.dashPayProfile.value, true)
                setNotificationIndicator()
            }
        }

        viewModel.notificationCountData.observe(viewLifecycleOwner) { setNotificationIndicator() }

        viewModel.coinJoinMode.observe(viewLifecycleOwner) { mode ->
            mixingBinding.root.isVisible = mode != CoinJoinMode.NONE
        }

        viewModel.mixingProgress.observe(viewLifecycleOwner) { progress ->
            updateMixedAndTotalBalance()
            mixingBinding.mixingPercent.text = getString(R.string.percent, progress.toInt())
            mixingBinding.mixingProgress.progress = progress.toInt()
        }

        viewModel.mixingState.observe(viewLifecycleOwner) { mixingState ->
            mixingBinding.root.isVisible = when (mixingState) {
                MixingStatus.NOT_STARTED,
                MixingStatus.ERROR,
                MixingStatus.FINISHED -> false
                else -> true
            }
            when (mixingState) {
                MixingStatus.MIXING, MixingStatus.FINISHING -> {
                    mixingBinding.mixingMode.text = getString(R.string.coinjoin_mixing)
                    mixingBinding.progressBar.isVisible = true
                }
                MixingStatus.PAUSED -> {
                    mixingBinding.mixingMode.text = getString(R.string.coinjoin_paused)
                    mixingBinding.progressBar.isVisible = false
                }
                else -> {
                    mixingBinding.mixingMode.text = getString(R.string.error)
                    mixingBinding.progressBar.isVisible = false
                }
            }
        }

        viewModel.mixingSessions.observe(viewLifecycleOwner) {
            val activeSessionsText = ".".repeat(it)
            mixingBinding.mixingSessions.text = activeSessionsText
        }

        viewModel.balance.observe(viewLifecycleOwner) {
            updateMixedAndTotalBalance()
        }

        viewModel.mixedBalance.observe(viewLifecycleOwner) {
            updateMixedAndTotalBalance()
        }

        viewModel.hasContacts.observe(viewLifecycleOwner) {
            refreshShortcutBar()
        }
    }

    private fun updateMixedAndTotalBalance() {
        mixingBinding.balance.text = getString(
            R.string.coinjoin_progress_balance,
            viewModel.mixedBalanceString,
            viewModel.walletBalanceString
        )
    }

    fun scrollToTop() {
        if (!isAdded) {
            return
        }

        binding.appBar.setExpanded(true)
        val walletTransactionsFragment = childFragmentManager
            .findFragmentByTag(TRANSACTIONS_FRAGMENT_TAG) as? WalletTransactionsFragment
        walletTransactionsFragment?.scrollToTop()
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
                    safeNavigate(WalletFragmentDirections.homeToBuySell())
                }
                binding.shortcutsPane.payToAddressButton -> {
                    handlePayToAddress()
                }
                binding.shortcutsPane.payToContactButton -> {
                    handleSelectContact()
                }
                binding.shortcutsPane.receiveButton -> {
                    viewModel.logEvent(AnalyticsConstants.Home.SHORTCUT_RECEIVE)
                    findNavController().navigate(
                        R.id.paymentsFragment,
                        bundleOf(
                            PaymentsFragment.ARG_ACTIVE_TAB to PaymentsFragment.ACTIVE_TAB_RECEIVE
                        )
                    )
                }
                binding.shortcutsPane.importPrivateKey -> {
                    SweepWalletActivity.start(requireContext(), true)
                }
                binding.shortcutsPane.explore -> {
                    viewModel.logEvent(AnalyticsConstants.Home.SHORTCUT_EXPLORE)
                    findNavController().navigate(
                        R.id.exploreFragment,
                        bundleOf(),
                        NavOptions.Builder()
                            .setEnterAnim(R.anim.slide_in_bottom)
                            .build()
                    )
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
        refreshIfUserHasIdentity()
    }

    private fun showHideSecureAction() {
        binding.shortcutsPane.isPassphraseVerified = viewModel.isPassphraseVerified
    }

    private fun refreshIfUserHasBalance() {
        val balance: Coin = viewModel.balance.value ?: Coin.ZERO
        binding.shortcutsPane.userHasBalance = balance.isPositive
    }

    private fun refreshIfUserHasIdentity() {
        binding.shortcutsPane.userHasContacts = viewModel.hasIdentity && viewModel.hasContacts.value
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
        lifecycleScope.launch {
            val pin = authManager.authenticate(requireActivity())
            pin?.let { startVerifySeedActivity(pin) }
        }
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
        val intent: Intent = VerifySeedActivity.createIntent(requireContext(), pin, false)
        startActivity(intent)
    }

    private fun handleSelectContact() {
        viewModel.logEvent(AnalyticsConstants.UsersContacts.SHORTCUT_SEND_TO_CONTACT)
        safeNavigate(
            WalletFragmentDirections.homeToContacts(
                ShowNavBar = false,
                mode = ContactsScreenMode.SELECT_CONTACT
            )
        )
    }

    private fun handlePayToAddress() {
        viewModel.logEvent(AnalyticsConstants.Home.SHORTCUT_SEND_TO_ADDRESS)
        safeNavigate(WalletFragmentDirections.homeToAddressInput())
    }

    private fun handleString(input: String, errorDialogTitleResId: Int, cannotClassifyCustomMessageResId: Int) {
        object : StringInputParser(input, true) {
            override fun handlePaymentIntent(paymentIntent: PaymentIntent) {
                SendCoinsActivity.start(requireActivity(), paymentIntent)
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
                    R.drawable.ic_error,
                    getString(errorDialogTitleResId),
                    if (messageArgs.isNotEmpty()) {
                        getString(messageResId, *messageArgs)
                    } else {
                        getString(messageResId)
                    },
                    getString(R.string.button_close),
                    null
                )
                dialog.isMessageSelectable = true
                dialog.show(requireActivity()) {
                    viewModel.logEvent(AnalyticsConstants.Home.NO_ADDRESS_COPIED)
                }
            }

            override fun cannotClassify(input: String) {
                log.info("cannot classify: '{}'", input)
                error(null, cannotClassifyCustomMessageResId, input)
            }
        }.parse()
    }

    private fun setNotificationIndicator() {
        binding.notificationBell.isVisible = viewModel.hasIdentity
        binding.notificationBell.setImageResource(
            if (viewModel.notificationCount > 0) {
                R.drawable.ic_new_notifications
            } else {
                R.drawable.ic_notification_bell
            }
        )
    }
}
