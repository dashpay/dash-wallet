/*
 * Copyright 2023 Dash Core Group.
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

package de.schildbach.wallet.ui.payments

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import de.schildbach.wallet.ui.AbstractBindServiceActivity
import de.schildbach.wallet.ui.scan.ScanActivity
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.ActivitySweepWalletBinding
import org.bitcoinj.core.PrefixedChecksummedBytes

class SweepWalletActivity: AbstractBindServiceActivity() {
    companion object {
        const val INTENT_EXTRA_KEY = "sweep_key"
        const val INTENT_EXTRA_USER_AUTHORIZED = "user_authorized"

        fun start(context: Context, userAuthorized: Boolean) {
            val intent = Intent(context, SweepWalletActivity::class.java)
            intent.putExtra(INTENT_EXTRA_USER_AUTHORIZED, userAuthorized)
            context.startActivity(intent)
        }

        fun start(context: Context, key: PrefixedChecksummedBytes?, userAuthorized: Boolean) {
            val intent = Intent(context, SweepWalletActivity::class.java)
            intent.putExtra(INTENT_EXTRA_KEY, key)
            intent.putExtra(INTENT_EXTRA_USER_AUTHORIZED, userAuthorized)
            context.startActivity(intent)
        }
    }

    private lateinit var binding: ActivitySweepWalletBinding

    /** Scan result to be consumed by [SweepWalletFragment] via [getScanResult]. */
    var scanResult: String? = null
        private set

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            scanResult = result.data?.getStringExtra(ScanActivity.INTENT_EXTRA_RESULT)
            // Fragment will pick up scanResult in onResume
            val fragment = supportFragmentManager.findFragmentById(R.id.sweep_wallet_fragment)
            if (fragment is SweepWalletFragment) {
                fragment.handleScanResult(scanResult)
            }
        } else {
            // User cancelled the scanner — close the activity
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySweepWalletBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.appbar.toolbar.title = getString(R.string.sweep_wallet_activity_title)
        binding.appbar.toolbar.setNavigationOnClickListener { finish() }
        walletApplication.startBlockchainService(false)

        // If no key was provided, launch the scanner immediately
        if (savedInstanceState == null && !intent.hasExtra(INTENT_EXTRA_KEY)) {
            scanLauncher.launch(Intent(this, ScanActivity::class.java))
        }
    }

    fun isUserAuthorized(): Boolean {
        return intent.getBooleanExtra(INTENT_EXTRA_USER_AUTHORIZED, false)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.activity_stay, R.anim.slide_out_left)
    }
}
