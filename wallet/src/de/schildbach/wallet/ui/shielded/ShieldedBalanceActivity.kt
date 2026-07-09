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

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet.ui.LockScreenActivity
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.scan.ScanActivity

/**
 * Host of the flag-gated shielded-balances UI (Figma canvas 231:200
 * "Payments"): the Receive/Internal/Send hub, the internal-transfer flow
 * and the send-to-address flow. Entry point: the "Shielded balance" row in
 * Settings, visible only when `Constants.SUPPORTS_PLATFORM` and the
 * `USE_KOTLIN_SDK_SHIELDED` flag are both on.
 */
@AndroidEntryPoint
class ShieldedBalanceActivity : LockScreenActivity() {

    companion object {
        @JvmStatic
        fun createIntent(context: Context): Intent =
            Intent(context, ShieldedBalanceActivity::class.java)
    }

    private enum class Screen { Home, Transfer, Send }

    private val transferViewModel: ShieldedTransferViewModel by viewModels()
    private val sendViewModel: ShieldedSendViewModel by viewModels()

    /** Compose reads this to prefill the send screen after a QR scan. */
    private var scannedAddress by mutableStateOf<String?>(null)

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanned = result.data?.getStringExtra(ScanActivity.INTENT_EXTRA_RESULT)?.trim()
            if (!scanned.isNullOrEmpty()) {
                sendViewModel.reset(prefillAddress = scanned)
                scannedAddress = scanned
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Constants.SUPPORTS_PLATFORM) {
            finish()
            return
        }

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                var screen by rememberSaveable { mutableStateOf(Screen.Home) }

                // A completed QR scan jumps to the (pre-filled) send screen.
                val scanned = scannedAddress
                androidx.compose.runtime.LaunchedEffect(scanned) {
                    if (scanned != null) {
                        screen = Screen.Send
                        scannedAddress = null
                    }
                }

                BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

                when (screen) {
                    Screen.Home -> ShieldedHomeScreen(
                        onBackClick = { finish() },
                        onInternalTransferClick = {
                            transferViewModel.reset()
                            screen = Screen.Transfer
                        },
                        onScanQrClick = {
                            scanLauncher.launch(ScanActivity.getIntent(this@ShieldedBalanceActivity))
                        },
                        onSendToAddressClick = {
                            sendViewModel.reset()
                            screen = Screen.Send
                        },
                        onCopyAddress = ::copyAddress,
                        onShareAddress = ::shareAddress
                    )
                    Screen.Transfer -> ShieldedTransferScreen(
                        viewModel = transferViewModel,
                        onBackClick = { screen = Screen.Home },
                        onFinished = { screen = Screen.Home }
                    )
                    Screen.Send -> ShieldedSendScreen(
                        viewModel = sendViewModel,
                        onBackClick = { screen = Screen.Home },
                        onFinished = { screen = Screen.Home }
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
