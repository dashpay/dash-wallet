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

package de.schildbach.wallet.ui.coinbase

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.BaseMenuActivity
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.integration.coinbase_integration.viewmodels.CoinbaseActivityViewModel

@AndroidEntryPoint
class CoinbaseActivity : BaseMenuActivity() {
    private val viewModel: CoinbaseActivityViewModel by viewModels()
    private lateinit var navController: NavController

    override fun getLayoutId(): Int {
        return R.layout.activity_coinbase
    }

    private val coinbaseAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data

        if (result.resultCode == Activity.RESULT_OK) {
            data?.extras?.getString(CoinBaseWebClientActivity.RESULT_TEXT)?.let { code ->
                handleCoinbaseAuthResult(code)
            }
        }
    }

    private fun handleCoinbaseAuthResult(code: String) {
        lifecycleScope.launchWhenResumed {
            val success = AdaptiveDialog.withProgress(getString(R.string.loading), this@CoinbaseActivity) {
                viewModel.loginToCoinbase(code)
            }

            if (success) {
                val intent = intent
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                finish()
                overridePendingTransition(0, 0)
                startActivity(intent)
                overridePendingTransition(0, 0)
            } else {
                AdaptiveDialog.create(
                    R.drawable.ic_error,
                    getString(R.string.login_error_title, getString(R.string.coinbase)),
                    getString(R.string.login_error_message, getString(R.string.coinbase)),
                    getString(android.R.string.cancel),
                    getString(R.string.retry)
                ).show(this@CoinbaseActivity) { retry ->
                    if (retry == true) {
                        handleCoinbaseAuthResult(code)
                    }
                }
            }
        }
    }

    private fun continueCoinbase() {
        coinbaseAuthLauncher.launch(
            Intent(
                this,
                CoinBaseWebClientActivity::class.java
            )
        )
    }

    private val reLoginDialog: AdaptiveDialog by lazy {
        AdaptiveDialog.create(
            R.drawable.ic_relogin,
            getString(R.string.your_coinbase_session_has_expired),
            getString(R.string.please_log_in_to_your_coinbase_account),
            getString(R.string.cancel),
            getString(R.string.log_in)
        ).also {
            it.isCancelable = false
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        navController = setNavigationGraph()

        viewModel.coinbaseLogOutCallback.observe(this) {
            if (reLoginDialog.isVisible) {
                reLoginDialog.dismiss()
            }
            if (it == true) {
                reLoginDialog.show(this) { login ->
                    if (login == true) {
                        continueCoinbase()
                    } else {
                        finish()
                    }
                }
            }
        }

        viewModel.getPaymentMethods()
        viewModel.getBaseIdForFaitModel()
    }

    private fun setNavigationGraph(): NavController {
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_coinbase_fragment) as NavHostFragment
        return navHostFragment.navController
    }

    override fun onLockScreenActivated() {
        if (navController.currentDestination?.id == R.id.enterTwoFaCodeFragment) {
            navController.popBackStack(
                org.dash.wallet.integration.coinbase_integration.R.id.coinbaseServicesFragment,
                false
            )
        }
    }
}
