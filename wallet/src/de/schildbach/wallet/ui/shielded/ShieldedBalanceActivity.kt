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
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet.ui.LockScreenActivity
import de.schildbach.wallet_test.R

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

        /** "Internal transfer" flow (Figma 1746:18462 / 1746:18478). */
        const val SCREEN_TRANSFER = 0

        /** "Send to shielded address" flow (parked; no UI entry point yet). */
        const val SCREEN_SEND = 1

        /** Shielded receive QR (parked; no UI entry point yet). */
        const val SCREEN_RECEIVE = 2

        @JvmStatic
        @JvmOverloads
        fun createIntent(context: Context, screen: Int = SCREEN_TRANSFER): Intent =
            Intent(context, ShieldedBalanceActivity::class.java)
                .putExtra(EXTRA_SCREEN, screen)
    }

    private val transferViewModel: ShieldedTransferViewModel by viewModels()
    private val sendViewModel: ShieldedSendViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Constants.SUPPORTS_PLATFORM) {
            finish()
            return
        }

        val screen = intent.getIntExtra(EXTRA_SCREEN, SCREEN_TRANSFER)
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
                        onFinished = { finish() }
                    )
                }
            }
        }
        setContentView(composeView)
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
