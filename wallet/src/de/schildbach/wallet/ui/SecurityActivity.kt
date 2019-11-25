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

import android.os.Bundle
import android.view.View
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.DialogBuilder
import org.slf4j.LoggerFactory

class SecurityActivity : BaseMenuActivity() {

    private val log = LoggerFactory.getLogger(SecurityActivity::class.java)

    override fun getLayoutId(): Int {
        return R.layout.activity_security
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setTitle(R.string.security_title)
    }

    private fun resetBlockchain() {
        val dialog = DialogBuilder(this)
        dialog.setTitle(R.string.preferences_initiate_reset_title)
        dialog.setMessage(R.string.preferences_initiate_reset_dialog_message)
        dialog.setPositiveButton(R.string.preferences_initiate_reset_dialog_positive) { _, _ ->
            log.info("manually initiated blockchain reset")

            WalletApplication.getInstance().resetBlockchain()
            finish()
        }
        dialog.setNegativeButton(R.string.button_dismiss, null)
        dialog.show()
    }

    fun viewRecoveryPhrase(view: View) {
        BackupWalletToSeedDialogFragment.show(supportFragmentManager)
    }
    fun changePin(view: View) {
        throw NotImplementedError()
    }
    fun openAdvancedSecurity(view: View) {
        throw NotImplementedError()
    }
    fun resetWallet(view: View) {
        throw NotImplementedError()
    }
}
