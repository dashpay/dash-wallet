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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import androidx.core.os.bundleOf
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.ui.send.SweepWalletActivity
import de.schildbach.wallet.util.Qr
import de.schildbach.wallet.util.Toast
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_tools.*
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.uri.BitcoinURIParseException
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.ui.BaseAlertDialogBuilder
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.slf4j.LoggerFactory
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class ToolsActivity : BaseMenuActivity() {

    companion object {
        private val log = LoggerFactory.getLogger(ToolsActivity::class.java)
    }

    @Inject
    lateinit var analytics: AnalyticsService

    override fun getLayoutId(): Int {
        return R.layout.activity_tools
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setTitle(R.string.tools_title)
        address_book.setOnClickListener {
            analytics.logEvent(AnalyticsConstants.Settings.ADDRESS_BOOK, bundleOf())
            startActivity(Intent(this, AddressBookActivity::class.java))
        }
        import_keys.setOnClickListener {
            analytics.logEvent(AnalyticsConstants.Settings.IMPORT_PRIVATE_KEY, bundleOf())
            startActivity(Intent(this, SweepWalletActivity::class.java))
        }
        network_monitor.setOnClickListener {
            analytics.logEvent(AnalyticsConstants.Settings.NETWORK_MONITORING, bundleOf())
            startActivity(Intent(this, NetworkMonitorActivity::class.java))
        }

        show_xpub.setOnClickListener {
            handleExtendedPublicKey()
        }

        transaction_export.setOnClickListener {
            alertDialog = ExportTransactionHistoryDialogBuilder.createExportTransactionDialog(this,
                WalletApplication.getInstance()).buildAlertDialog()
            alertDialog.show()
        }
    }

    private fun handleExtendedPublicKey() {
        val extendedKey: DeterministicKey = walletApplication.wallet.watchingKey
        val xpub = extendedKey.serializePubB58(Constants.NETWORK_PARAMETERS);
        val xpubWithCreationDate = String.format(
            Locale.US,
            "%s?c=%d&h=bip44",
            xpub,
            extendedKey.creationTimeSeconds
        )
        showExtendedPublicKeyDialog(xpubWithCreationDate, xpub)
    }

    private fun showExtendedPublicKeyDialog(xpubWithCreationDate: String, xpub: String) {
        val view = LayoutInflater.from(this).inflate(R.layout.extended_public_key_dialog, null)
        val bitmap = BitmapDrawable(
            resources, Qr.bitmap(xpubWithCreationDate)
        )
        bitmap.isFilterBitmap = false
        val imageView = view.findViewById<ImageView>(R.id.extended_public_key_dialog_image)
        val xpubView = view.findViewById<TextView>(R.id.extended_public_key_dialog_xpub)
        imageView.setImageDrawable(bitmap)
        xpubView.text = xpub

        xpubView.setOnClickListener {
            handleCopyAddress(xpub)
        }

        val baseAlertDialogBuilder = BaseAlertDialogBuilder(this)
        baseAlertDialogBuilder.view = view
        baseAlertDialogBuilder.negativeText = getString(R.string.button_dismiss)
        baseAlertDialogBuilder.positiveText = getString(R.string.button_share)
        baseAlertDialogBuilder.positiveAction = {
            createAndLaunchShareIntent(xpubWithCreationDate)
            Unit
        }
        alertDialog = baseAlertDialogBuilder.buildAlertDialog()
        alertDialog.show()

        AdaptiveDialog
    }

    private fun createAndLaunchShareIntent(xpub: String) {
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_TEXT, xpub)
        intent.putExtra(
            Intent.EXTRA_SUBJECT,
            getString(R.string.extended_public_key_fragment_title)
        )
        startActivity(
            Intent.createChooser(
                intent,
                getString(R.string.extended_public_key_fragment_share)
            )
        )
        log.info("xpub shared via intent: {}", xpub)
    }

    private fun handleCopyAddress(xpub: String) {
        val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.setPrimaryClip(ClipData.newPlainText("Dash extendeded public key", xpub))

        Toast(this).toast(R.string.receive_copied)
        log.info("xpub copied to clipboard: {}", xpub)
    }
}
