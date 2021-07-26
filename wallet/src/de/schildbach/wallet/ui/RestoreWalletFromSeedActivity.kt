/*
 * Copyright 2020 Dash Core Group
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
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.backup.RestoreFromFileActivity
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_forgot_pin.*
import kotlinx.android.synthetic.main.activity_recover_wallet_from_seed.*
import org.bitcoinj.crypto.MnemonicException
import java.util.*


class RestoreWalletFromSeedActivity : RestoreFromFileActivity() {

    companion object {

        private const val EXTRA_RECOVERY_PIN_MODE = "recovery_pin_mode"

        @JvmStatic
        fun createIntent(context: Context, recoveryPinMode: Boolean = false): Intent {
            return Intent(context, RestoreWalletFromSeedActivity::class.java).apply {
                putExtra(EXTRA_RECOVERY_PIN_MODE, recoveryPinMode)
            }
        }
    }

    private lateinit var viewModel: RestoreWalletFromSeedViewModel

    private lateinit var walletApplication: WalletApplication

    private val recoveryPinMode by lazy {
        intent?.extras?.getBoolean(EXTRA_RECOVERY_PIN_MODE) ?: false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_recover_wallet_from_seed)

        setSupportActionBar(toolbar)
        val actionBar = supportActionBar
        actionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }

        setTitle(R.string.recover_wallet_title)

        walletApplication = (application as WalletApplication)

        viewModel = ViewModelProvider(this)[RestoreWalletFromSeedViewModel::class.java]

        initView()
        initViewModel()
    }

    private fun initView() {
        input.requestFocus()
        input.addTextChangedListener(object : TextWatcher {
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                submit.isEnabled = s.toString().trim().isNotEmpty()
            }

            override fun afterTextChanged(s: Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        })
        submit.setOnClickListener {
            walletApplication.initEnvironmentIfNeeded()
            val seed = input.text.trim().replace(Regex(" +"), " ")
            if (seed.isNotEmpty()) {
                val words = ArrayList(mutableListOf(*seed.split(' ').toTypedArray()))
                if (recoveryPinMode) {
                    viewModel.recoverPin(words)
                } else {
                    viewModel.restoreWalletFromSeed(words)
                }
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
            val errorMessage = when (it) {
                is MnemonicException.MnemonicLengthException -> walletApplication.getString(R.string.restore_wallet_from_invalid_seed_not_twelve_words)
                is MnemonicException.MnemonicChecksumException -> walletApplication.getString(R.string.restore_wallet_from_invalid_seed_bad_checksum)
                is MnemonicException.MnemonicWordException -> walletApplication.getString(R.string.restore_wallet_from_invalid_seed_warning_message, it.badWord)
                else -> walletApplication.getString(R.string.restore_wallet_from_invalid_seed_failure, message)
            }
            showErrorDialog(errorMessage)
        })
        viewModel.recoverPinLiveData.observe(this, Observer {
            when (it.status) {
                Status.SUCCESS -> {
                    startActivity(SetPinActivity.createIntent(this, R.string.set_pin_set_pin, true, it.data))
                }
                Status.LOADING -> {
                    // ignore
                }
                Status.ERROR -> {
                    showErrorDialog(getString(R.string.forgot_pin_passphrase_doesnt_match))
                }
            }
        })
        viewModel.startActivityAction.observe(this, Observer {
            startActivityForResult(it, SET_PIN_REQUEST_CODE)
        })
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showErrorDialog(errorMessage: String) {
        AlertDialog.Builder(this).apply {
            setTitle(R.string.import_export_keys_dialog_failure_title)
            setMessage(errorMessage)
            setPositiveButton(R.string.button_ok, null)
            show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SET_PIN_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            setResult(Activity.RESULT_OK)
            finish()
        }
    }
}
