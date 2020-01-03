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
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.util.WalletUtils
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_recover_wallet_from_seed.*
import org.bitcoinj.crypto.MnemonicCode
import org.bitcoinj.crypto.MnemonicException
import org.dash.wallet.common.ui.DialogBuilder
import org.slf4j.LoggerFactory
import java.util.*


class RestoreWalletFromSeedActivity : RestoreFromFileActivity() {
    private val log = LoggerFactory.getLogger(RestoreWalletFromSeedDialogFragment::class.java)

    private lateinit var viewModel: RestoreWalletFromSeedViewModel

    private lateinit var walletApplication: WalletApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_recover_wallet_from_seed)

        setTitle(R.string.recover_wallet_title)

        walletApplication = (application as WalletApplication)

        viewModel = ViewModelProviders.of(this).get(RestoreWalletFromSeedViewModel::class.java)

        initView()
        initViewModel()
    }

    private fun initView() {
        input.requestFocus()
        input.addTextChangedListener(object : TextWatcher {
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                submit.isEnabled = s.toString().trim().isNotEmpty()
            }
            override fun afterTextChanged(s: Editable?) { }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { }
        })
        submit.setOnClickListener {
            walletApplication.initEnvironmentIfNeeded()
            val seed = input.text.trim()
            if (seed.isNotEmpty()) {
                val words = ArrayList(mutableListOf(*seed.split(' ').toTypedArray()))
                viewModel.restoreWalletFromSeed(words)
            }
        }
    }

    @SuppressLint("StringFormatInvalid")
    private fun initViewModel() {
        viewModel.showRestoreWalletFailureAction.observe(this, Observer {
            val message = when {
                TextUtils.isEmpty(it.message) -> it.javaClass.simpleName
                else -> it.message!!
            }
            val dialog = DialogBuilder.warn(this, R.string.import_export_keys_dialog_failure_title)

            val errorMessage = when (it) {
                is MnemonicException.MnemonicLengthException -> walletApplication.getString(R.string.restore_wallet_from_invalid_seed_not_twelve_words)
                is MnemonicException.MnemonicChecksumException -> walletApplication.getString(R.string.restore_wallet_from_invalid_seed_bad_checksum)
                is MnemonicException.MnemonicWordException -> walletApplication.getString(R.string.restore_wallet_from_invalid_seed_warning_message, it.badWord)
                else -> walletApplication.getString(R.string.restore_wallet_from_invalid_seed_failure, message)
            }
            dialog.setMessage(errorMessage)
            dialog.setPositiveButton(R.string.button_dismiss, null)
            dialog.show()
        })
        viewModel.startActivityAction.observe(this, Observer {
            startActivity(it)
        })
    }
}
