/*
 * Copyright 2026 Dash Core Group.
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

package de.schildbach.wallet.ui.shielded

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet.ui.LockScreenActivity
import de.schildbach.wallet.ui.main.MainActivity
import de.schildbach.wallet.ui.more.MoreFragment
import de.schildbach.wallet_test.R
import kotlinx.coroutines.launch
import org.dash.wallet.common.services.AuthenticationManager
import javax.inject.Inject

/** Where a dismissed terminal transfer outcome leaves the flow — see [shieldedTransferExitTarget]. */
internal enum class ShieldedExitTarget { FINISH, HOME }

/**
 * Where dismissing a terminal, non-success transfer outcome (Ambiguous /
 * LockedPendingShield / Stalled) must take the user — pure, host-testable.
 *
 * - Opened from the More/payments card: the activity sits directly on that
 *   screen, so a plain finish() returns to it ([ShieldedExitTarget.FINISH]).
 * - Opened via the username/invite "Shield your funds first" path
 *   ([shieldFirst]): the activity sits ON TOP of the create-username/invite
 *   flow (and its still-open dialogs). A finish() would unwind that stack one
 *   screen at a time (live bug); instead clear straight to home in one step
 *   ([ShieldedExitTarget.HOME]), which also drops the username-flow dialogs.
 */
internal fun shieldedTransferExitTarget(shieldFirst: Boolean): ShieldedExitTarget =
    if (shieldFirst) ShieldedExitTarget.HOME else ShieldedExitTarget.FINISH

/**
 * Host of the flag-gated shielded-balances flows (Figma canvas 231:200
 * "Payments"): the internal-transfer flow (the default, opened from the
 * payments screen's "Internal" tab), plus the parked send-to-address and
 * receive screens, selected via [EXTRA_SCREEN]. Entry points require
 * `Constants.SUPPORTS_PLATFORM` and the `USE_KOTLIN_SDK_SHIELDED` flag.
 */
@AndroidEntryPoint
class ShieldedBalanceActivity : LockScreenActivity() {

    companion object {
        private const val EXTRA_SCREEN = "screen"

        /**
         * True when this activity was opened via the username/invite "Shield
         * your funds first" path (it sits on top of the create-username flow),
         * as opposed to the More/payments card. Controls where a dismissed
         * terminal outcome leaves the flow ([shieldedTransferExitTarget]).
         */
        private const val EXTRA_SHIELD_FIRST = "shield_first"

        /** "Internal transfer" flow (Figma 1746:18462 / 1746:18478). */
        const val SCREEN_TRANSFER = 0

        /** "Send to shielded address" flow (parked; no UI entry point yet). */
        const val SCREEN_SEND = 1

        /** Shielded receive QR (parked; no UI entry point yet). */
        const val SCREEN_RECEIVE = 2

        @JvmStatic
        @JvmOverloads
        fun createIntent(
            context: Context,
            screen: Int = SCREEN_TRANSFER,
            shieldFirst: Boolean = false
        ): Intent =
            Intent(context, ShieldedBalanceActivity::class.java)
                .putExtra(EXTRA_SCREEN, screen)
                .putExtra(EXTRA_SHIELD_FIRST, shieldFirst)
                // Merely opening this screen must NOT re-prompt for the PIN
                // (the user already authenticated in-session to reach here) —
                // the SendCoins/TransactionResult precedent. The actual spend
                // is authorized at submit time by authenticateAndConfirmTransfer.
                .putExtra(LockScreenActivity.INTENT_EXTRA_KEEP_UNLOCKED, true)
    }

    private val transferViewModel: ShieldedTransferViewModel by viewModels()
    private val sendViewModel: ShieldedSendViewModel by viewModels()

    /**
     * The same PIN/biometric gate the send flows use (CrowdNode deposit,
     * gift-card purchase pattern: `securityFunctions.authenticate(activity)
     * ?: return`). The internal transfer moves real funds — the
     * Dash Wallet → Shielded direction spends the L1 balance via an
     * asset lock — so confirming requires the send-flow authentication.
     */
    @Inject
    lateinit var securityFunctions: AuthenticationManager

    /**
     * App-scoped owner of the transfer spend. The activity reports
     * whether the transfer UI is the resumed foreground screen so the
     * executor knows where to announce a finished operation: on-screen
     * state (here), a global in-app toast (foregrounded elsewhere) or a
     * system notification (backgrounded).
     */
    @Inject
    lateinit var transferExecutor: ShieldedTransferExecutor

    private var screen = SCREEN_TRANSFER
    private var shieldFirst = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Constants.SUPPORTS_PLATFORM) {
            finish()
            return
        }

        screen = intent.getIntExtra(EXTRA_SCREEN, SCREEN_TRANSFER)
        shieldFirst = intent.getBooleanExtra(EXTRA_SHIELD_FIRST, false)
        if (savedInstanceState == null) {
            when (screen) {
                SCREEN_TRANSFER -> transferViewModel.reset()
                SCREEN_SEND -> sendViewModel.reset()
            }
        }

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                when (screen) {
                    SCREEN_SEND -> ShieldedSendScreen(
                        viewModel = sendViewModel,
                        onBackClick = { finish() },
                        onFinished = { finish() }
                    )
                    SCREEN_RECEIVE -> ShieldedReceiveScreen(
                        onBackClick = { finish() },
                        onCopyAddress = ::copyAddress,
                        onShareAddress = ::shareAddress
                    )
                    else -> ShieldedTransferScreen(
                        viewModel = transferViewModel,
                        onBackClick = { finish() },
                        onFinished = ::leaveTransferAfterOutcome,
                        onConfirm = ::authenticateAndConfirmTransfer,
                        onSuccess = ::navigateToMoreAfterTransfer
                    )
                }
            }
        }
        setContentView(composeView)
    }

    override fun onResume() {
        super.onResume()
        if (screen == SCREEN_TRANSFER) {
            transferExecutor.setTransferUiVisible(this, true)
        }
    }

    override fun onPause() {
        transferExecutor.setTransferUiVisible(this, false)
        super.onPause()
    }

    /**
     * A recreation (or any teardown that skipped straight past onPause)
     * must leave the executor reading "not visible": the composition that
     * renders a terminal outcome is gone with this instance. Owner-scoped
     * so this late callback — it arrives AFTER the replacement instance's
     * onResume — can never clear the NEW screen's visibility.
     */
    override fun onDestroy() {
        transferExecutor.setTransferUiVisible(this, false)
        super.onDestroy()
    }

    /**
     * PIN/biometric first, then the spend. A dismissed or failed
     * authentication simply returns — the confirm sheet stays open and
     * nothing is submitted.
     */
    private fun authenticateAndConfirmTransfer() {
        lifecycleScope.launch {
            securityFunctions.authenticate(this@ShieldedBalanceActivity) ?: return@launch
            transferViewModel.onConfirm()
        }
    }

    /**
     * AC12: a completed transfer leaves the flow entirely — land the user
     * on the More screen (its balance cards show the moved funds) with the
     * "Transfer completed" toast (Figma 1691:15460). SINGLE_TOP|CLEAR_TOP
     * delivers the destination to the existing MainActivity beneath this
     * one and pops everything above it; finish() covers the cold-start case.
     */
    /**
     * A terminal, non-success transfer outcome (Ambiguous / LockedPendingShield
     * / Stalled) was dismissed. From the More/payments card a plain finish()
     * returns to it; from the "Shield your funds first" path this activity is
     * stacked on the create-username/invite flow, so finishing would unwind
     * that stack (with its dialogs) one screen at a time — go straight home
     * instead, clearing the whole flow in one step (Bug 1).
     */
    private fun leaveTransferAfterOutcome() {
        when (shieldedTransferExitTarget(shieldFirst)) {
            ShieldedExitTarget.HOME -> {
                startActivity(MainActivity.createIntent(this))
                finish()
            }
            ShieldedExitTarget.FINISH -> finish()
        }
    }

    private fun navigateToMoreAfterTransfer() {
        startActivity(
            MainActivity.createIntent(
                this,
                R.id.moreFragment,
                bundleOf(MoreFragment.ARG_SHOW_TRANSFER_COMPLETED_TOAST to true)
            )
        )
        finish()
    }

    private fun copyAddress(address: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.shielded_your_address), address)
        )
        Toast.makeText(this, R.string.shielded_address_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareAddress(address: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, address)
        }
        startActivity(
            Intent.createChooser(intent, getString(R.string.shielded_share_address))
        )
    }
}
