/*
 * Copyright 2021 Dash Core Group.
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

package de.schildbach.wallet.ui.staking

import android.os.Bundle
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.*
import de.schildbach.wallet_test.databinding.ActivityStakingBinding
import kotlinx.coroutines.launch
import org.dash.wallet.integrations.crowdnode.ui.CrowdNodeViewModel
import org.dash.wallet.integrations.crowdnode.ui.NavigationRequest

@AndroidEntryPoint
class StakingActivity : LockScreenActivity() {
    private val viewModel: CrowdNodeViewModel by viewModels()
    private lateinit var binding: ActivityStakingBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStakingBinding.inflate(layoutInflater)

        viewModel.navigationCallback.observe(this) { request ->
            when (request) {
                NavigationRequest.BackupPassphrase -> checkPinAndBackupPassphrase()
                NavigationRequest.RestoreWallet -> {
                    ResetWalletDialog.newInstance().show(supportFragmentManager, "reset_wallet_dialog")
                }
                NavigationRequest.BuyDash -> {
                    startActivity(BuyAndSellLiquidUpholdActivity.createIntent(this))
                }
            }
        }

        setContentView(binding.root)
    }

    private fun checkPinAndBackupPassphrase() {
        lifecycleScope.launch {
            val pin = CheckPinDialog.showAsync(this@StakingActivity)

            if (pin != null) {
                val intent = VerifySeedActivity.createIntent(this@StakingActivity, pin)
                startActivity(intent)
            }
        }
    }
}