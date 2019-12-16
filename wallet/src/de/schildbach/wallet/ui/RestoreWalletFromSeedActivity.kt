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

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import de.schildbach.wallet.Constants
import de.schildbach.wallet.util.WalletUtils
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_recover_wallet_from_seed.*
import org.bitcoinj.crypto.MnemonicCode
import org.bitcoinj.crypto.MnemonicException
import org.dash.wallet.common.ui.DialogBuilder
import org.slf4j.LoggerFactory
import java.util.*


class RestoreWalletFromSeedActivity : BaseMenuActivity() {
    private val log = LoggerFactory.getLogger(RestoreWalletFromSeedDialogFragment::class.java)


    override fun getLayoutId(): Int {
        return R.layout.activity_recover_wallet_from_seed
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setTitle(R.string.recover_wallet_title)
        input.requestFocus()
        input.addTextChangedListener(object : TextWatcher {
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                submit.isEnabled = s.toString().trim().isNotEmpty()
            }
            override fun afterTextChanged(s: Editable?) { }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { }
        })

    }

    @SuppressLint("StringFormatInvalid")
    fun onContinueClick(view: View) {
        val seed = input.text.trim()
        if (seed.isEmpty()) {
            return
        }

        val words = ArrayList(mutableListOf(*seed.split(' ').toTypedArray()))

        try {
            if(words.size != 12)
                throw MnemonicException.MnemonicLengthException("There are not 12 words")
            MnemonicCode.INSTANCE.check(words)
        } catch (x: MnemonicException) {
            log.info("problem restoring wallet from seed: ", x)
            val dialog = DialogBuilder.warn(this, R.string.import_export_keys_dialog_failure_title)
            var errorMessage = getString(R.string.import_keys_dialog_failure, x.message)
            if(x is MnemonicException.MnemonicLengthException)
                errorMessage = getString(R.string.restore_wallet_from_invalid_seed_not_twelve_words)
            else if(x is MnemonicException.MnemonicChecksumException)
                errorMessage = getString(R.string.restore_wallet_from_invalid_seed_bad_checksum)
            else if(x is MnemonicException.MnemonicWordException) {
                var firstInvalidWord = "unknown"
                for(word in words) {
                    if(MnemonicCode.INSTANCE.wordList.lastIndexOf(word) == -1) {
                        firstInvalidWord = word
                        break
                    }
                }
                errorMessage = getString(R.string.restore_wallet_from_invalid_seed_warning_message, firstInvalidWord)
            }
            dialog.setMessage(errorMessage)
            dialog.setPositiveButton(R.string.button_dismiss, null)
            dialog.show()
            return
        }

        walletApplication.wallet = WalletUtils
                .restoreWalletFromSeed(words, Constants.NETWORK_PARAMETERS)
        log.info("successfully restored wallet from seed")
        walletApplication.configuration.disarmBackupSeedReminder()
        walletApplication.configuration.isRestoringBackup = true

        val dialog = DialogBuilder(this)
        dialog.setTitle(R.string.restore_wallet_dialog_success)
        dialog.setMessage(getString(R.string.restore_wallet_dialog_success_replay))
        dialog.setPositiveButton(R.string.button_ok) { _, _ ->
            val intent = SetPinActivity
                    .createIntent(walletApplication, R.string.set_pin_create_new_wallet)
            startActivity(intent)
        }
        dialog.show()
    }
}
