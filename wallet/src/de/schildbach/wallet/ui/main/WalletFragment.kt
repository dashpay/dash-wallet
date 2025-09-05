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
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.asFlow
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.AppBarLayout.Behavior.DragCallback
import com.google.android.material.transition.MaterialFadeThrough
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.data.PaymentIntent
import de.schildbach.wallet.data.ServiceType
import de.schildbach.wallet.service.CoinJoinMode
import de.schildbach.wallet.service.MixingStatus
import de.schildbach.wallet.ui.EditProfileActivity
import de.schildbach.wallet.ui.LockScreenActivity
import de.schildbach.wallet.ui.coinjoin.CoinJoinActivity
import de.schildbach.wallet.ui.compose_views.ComposeBottomSheet
import de.schildbach.wallet.ui.dashpay.ContactsScreenMode
import de.schildbach.wallet.ui.dashpay.NotificationsFragment
import de.schildbach.wallet.ui.dashpay.utils.display
import de.schildbach.wallet.ui.main.shortcuts.ShortcutOption
import de.schildbach.wallet.ui.main.shortcuts.ShortcutsList
import de.schildbach.wallet.ui.main.shortcuts.ShortcutsPane
import de.schildbach.wallet.ui.main.shortcuts.ShortcutsViewModel
import de.schildbach.wallet.ui.payments.PaymentsFragment
import de.schildbach.wallet.ui.payments.SweepWalletActivity
import de.schildbach.wallet.ui.scan.ScanActivity
import de.schildbach.wallet.ui.send.SendCoinsActivity
import de.schildbach.wallet.ui.staking.StakingActivity
import de.schildbach.wallet.ui.transactions.TaxCategoryExplainerDialogFragment
import de.schildbach.wallet.ui.transactions.TransactionDetailsDialogFragment
import de.schildbach.wallet.ui.util.InputParser.StringInputParser
import de.schildbach.wallet.ui.verify.VerifySeedActivity
import de.schildbach.wallet.util.WalletUtils
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.HomeContentBinding
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.core.PrefixedChecksummedBytes
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.VerificationException
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.services.AuthenticationManager
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.avatar.ProfilePictureDisplay
import org.dash.wallet.common.ui.components.InfoPanel
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.observe
import org.dash.wallet.common.util.openCustomTab
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.features.exploredash.ui.explore.ExploreTopic
import org.slf4j.LoggerFactory
import javax.inject.Inject

@AndroidEntryPoint
class WalletFragment : Fragment(R.layout.home_content) {
    companion object {
        private val log = LoggerFactory.getLogger(WalletFragment::class.java)
        private const val TRANSACTIONS_FRAGMENT_TAG = "wallet_transactions_fragment"
    }

    private val viewModel: MainViewModel by activityViewModels()
    private val shortcutViewModel: ShortcutsViewModel by activityViewModels()
    private val binding by viewBinding(HomeContentBinding::bind)
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

    private val stakingLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Constants.USER_BUY_SELL_DASH) {
            safeNavigate(WalletFragmentDirections.homeToBuySell())
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        reenterTransition = MaterialFadeThrough()
        initShortcutActions()

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

        binding.infoPanel.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )

        binding.infoPanel.setContent {
            if (shortcutViewModel.showShortcutInfo) {
                InfoPanel(
                    stringResource(R.string.customize_shortcuts),
                    stringResource(R.string.customize_shortcuts_description),
                    modifier = Modifier
                        .wrapContentHeight()
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp),
                    leftIconRes = R.drawable.ic_shortcuts,
                    actionIconRes = R.drawable.ic_popup_close
                ) {
                    shortcutViewModel.hideShortcutInfo()
                }
            }
        }
        binding.composeMixingStatusPane.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.composeMixingStatusPane.setContent {
            MixingStatusCard(
                viewModel.coinJoinMode,
                viewModel.mixingState,
                viewModel.mixingProgress,
                viewModel.mixedBalance.asFlow(),
                viewModel.totalBalance.asFlow(),
                viewModel.hideBalance
            ) {
                startActivity(Intent(requireContext(), CoinJoinActivity::class.java).apply {
                    putExtra(CoinJoinActivity.FIRST_TIME_EXTRA, false)
                })
            }
        }

        viewModel.transactions.observe(viewLifecycleOwner) { refreshShortcutBar() }
        viewModel.isBlockchainSynced.observe(viewLifecycleOwner) { updateSyncState() }
        viewModel.isBlockchainSyncFailed.observe(viewLifecycleOwner) { updateSyncState() }

        if (!configuration.hasDisplayedTaxCategoryExplainer) {
            viewModel.observeMostRecentTransaction().observe(viewLifecycleOwner) { mostRecentTransaction: Transaction ->
                log.info("most recent transaction: {}", mostRecentTransaction.txId)

                if ((requireActivity() as? LockScreenActivity)?.lockScreenDisplayed != true &&
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

        viewModel.totalBalance.observe(viewLifecycleOwner) {
            val balance: Coin = viewModel.totalBalance.value ?: Coin.ZERO
            shortcutViewModel.userHasBalance = balance.isPositive
        }

        viewModel.hasContacts.observe(viewLifecycleOwner) {
            refreshShortcutBar()
        }
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
        shortcutViewModel.refreshIsPassphraseVerified()
    }

    private fun initShortcutActions() {
        binding.shortcutsPane.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )

        binding.shortcutsPane.setContent {
            ShortcutsPane(
                shortcuts = shortcutViewModel.shortcuts,
                onClick = { shortcut ->
                    onShortcutTap(shortcut)
                },
                onLongClick = { shortcut, index ->
                    onShortcutLongTap(shortcut, index)
                }
            )
        }

        refreshShortcutBar()
    }

    private fun refreshShortcutBar() {
        shortcutViewModel.refreshIsPassphraseVerified()
        shortcutViewModel.userHasContacts = viewModel.hasIdentity && viewModel.hasContacts.value
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

    private fun handleScan() {
        val intent = ScanActivity.getIntent(activity)
        scanLauncher.launch(intent)
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
                dialog.show(requireActivity())
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

    private fun onShortcutTap(shortcut: ShortcutOption) {
        when (shortcut) {
            ShortcutOption.SECURE_NOW -> {
                viewModel.logEvent(AnalyticsConstants.Home.SHORTCUT_SECURE_WALLET)
                handleVerifySeed()
            }
            ShortcutOption.SCAN_QR -> {
                viewModel.logEvent(AnalyticsConstants.Home.SHORTCUT_SCAN_TO_PAY)
                handleScan()
            }
            ShortcutOption.BUY_SELL -> {
                viewModel.logEvent(AnalyticsConstants.Home.SHORTCUT_BUY_AND_SELL)
                safeNavigate(WalletFragmentDirections.homeToBuySell())
            }
            ShortcutOption.SEND_TO_ADDRESS -> {
                handlePayToAddress()
            }
            ShortcutOption.SEND_TO_CONTACT -> {
                handleSelectContact()
            }
            ShortcutOption.SEND -> {
                viewModel.logEvent(AnalyticsConstants.Home.SHORTCUT_SEND)
                findNavController().navigate(
                    R.id.paymentsFragment,
                    bundleOf(
                        PaymentsFragment.ARG_ACTIVE_TAB to PaymentsFragment.ACTIVE_TAB_PAY
                    )
                )
            }
            ShortcutOption.RECEIVE -> {
                viewModel.logEvent(AnalyticsConstants.Home.SHORTCUT_RECEIVE)
                findNavController().navigate(
                    R.id.paymentsFragment,
                    bundleOf(
                        PaymentsFragment.ARG_ACTIVE_TAB to PaymentsFragment.ACTIVE_TAB_RECEIVE
                    )
                )
            }
            ShortcutOption.EXPLORE -> {
                viewModel.logEvent(AnalyticsConstants.Home.SHORTCUT_EXPLORE)
                findNavController().navigate(
                    R.id.exploreFragment,
                    bundleOf(),
                    NavOptions.Builder()
                        .setEnterAnim(R.anim.slide_in_bottom)
                        .build()
                )
            }
            ShortcutOption.WHERE_TO_SPEND -> {
                safeNavigate(WalletFragmentDirections.homeToSearch(type = ExploreTopic.Merchants))
            }
            ShortcutOption.ATMS -> {
                safeNavigate(WalletFragmentDirections.homeToSearch(type = ExploreTopic.ATMs))
            }
            ShortcutOption.STAKING -> {
                handleStakingNavigation()
            }
            ShortcutOption.TOPPER -> {
                lifecycleScope.launch {
                    val uri = shortcutViewModel.getTopperUrl(getString(R.string.dash_wallet_name))
                    requireActivity().openCustomTab(uri)
                }
            }
            ShortcutOption.UPHOLD -> {
                safeNavigate(WalletFragmentDirections.homeToUphold())
            }
            ShortcutOption.COINBASE -> {
                if (shortcutViewModel.isCoinbaseAuthenticated) {
                    safeNavigate(WalletFragmentDirections.homeToCoinbase())
                } else {
                    safeNavigate(WalletFragmentDirections.homeToBuySellOverview(ServiceType.COINBASE))
                }
            }
        }
    }

    private fun onShortcutLongTap(shortcut: ShortcutOption, index: Int) {
        if (shortcut == ShortcutOption.SECURE_NOW) {
            return
        }

        ComposeBottomSheet(R.style.SecondaryBackground, forceExpand = true) { dialog ->
            ShortcutsList(shortcutViewModel.getAllShortcutOptions(shortcut)) { newShortcut ->
                shortcutViewModel.replaceShortcut(index, newShortcut)
                dialog.dismiss()
            }
        }.show(requireActivity())

        shortcutViewModel.hideShortcutInfo() // Assume user is aware of this feature already
    }

    private fun handleStakingNavigation() {
        lifecycleScope.launch {
            if (viewModel.isBlockchainSynced.value == true) {
                stakingLauncher.launch(Intent(requireContext(), StakingActivity::class.java))
            } else {
                val openWebsite = AdaptiveDialog.create(
                    null,
                    getString(R.string.chain_syncing),
                    getString(R.string.crowdnode_wait_for_sync),
                    getString(R.string.button_close),
                    getString(R.string.crowdnode_open_website)
                ).showAsync(requireActivity())

                if (openWebsite == true) {
                    val browserIntent = Intent(Intent.ACTION_VIEW, getString(R.string.crowdnode_website).toUri())
                    startActivity(browserIntent)
                }
            }
        }
    }
}
