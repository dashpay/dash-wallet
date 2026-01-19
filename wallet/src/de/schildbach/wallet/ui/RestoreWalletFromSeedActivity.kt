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

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.Spanned
import android.app.DatePickerDialog
import android.text.TextUtils
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.ui.backup.RestoreFromFileActivity
import de.schildbach.wallet.ui.compose_views.createWalletCreationDateInfoDialog
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.ActivityRecoverWalletFromSeedBinding
import kotlinx.coroutines.launch
import org.bitcoinj.crypto.MnemonicException
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import java.text.SimpleDateFormat
import java.util.*
import java.util.Calendar
import java.util.Date
import javax.inject.Inject
import kotlin.collections.ArrayList

@AndroidEntryPoint
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

    private val viewModel: RestoreWalletFromSeedViewModel by viewModels()

    @Inject
    lateinit var walletApplication: WalletApplication
    private lateinit var binding: ActivityRecoverWalletFromSeedBinding

    private val recoveryPinMode by lazy {
        intent?.extras?.getBoolean(EXTRA_RECOVERY_PIN_MODE) ?: false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        super.setSecuredActivity(true)

        binding = ActivityRecoverWalletFromSeedBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.appBar.toolbar.title = getString(R.string.recover_wallet_title)
        binding.appBar.toolbar.setNavigationOnClickListener { finish() }
        initView()
    }

    private fun initView() {
        binding.input.requestFocus()
        binding.input.filters = arrayOf<InputFilter>(
            object : InputFilter.AllCaps() {
                override fun filter(
                    source: CharSequence,
                    start: Int,
                    end: Int,
                    dest: Spanned?,
                    dstart: Int,
                    dend: Int
                ): CharSequence {
                    return source.toString().lowercase(Locale.getDefault())
                }
            }
        )

        binding.input.addTextChangedListener(object : TextWatcher {
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                binding.submit.isEnabled = s.toString().trim().isNotEmpty()
            }

            override fun afterTextChanged(s: Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        })
        binding.submit.setOnClickListener {
            walletApplication.initEnvironmentIfNeeded()
            val seed = binding.input.text.trim().replace(Regex(" +"), " ")
            if (seed.isNotEmpty()) {
                val words = ArrayList(mutableListOf(*seed.split(' ').toTypedArray()))
                lifecycleScope.launch {
                    try {
                        restoreWallet(words)
                    } catch (ex: Exception) {
                        val message = when {
                            TextUtils.isEmpty(ex.message) -> ex.javaClass.simpleName
                            else -> ex.message!!
                        }
                        val errorMessage = when (ex) {
                            is MnemonicException.MnemonicLengthException -> walletApplication.getString(
                                R.string.restore_wallet_from_invalid_seed_not_twelve_words
                            )
                            is MnemonicException.MnemonicChecksumException -> walletApplication.getString(
                                R.string.restore_wallet_from_invalid_seed_bad_checksum
                            )
                            is MnemonicException.MnemonicWordException -> walletApplication.getString(
                                R.string.restore_wallet_from_invalid_seed_warning_message,
                                ex.badWord
                            )
                            else -> walletApplication.getString(
                                R.string.restore_wallet_from_invalid_seed_failure,
                                message
                            )
                        }
                        showErrorDialog(errorMessage)
                    }
                }
            }
        }

        // Date picker setup
        setupDatePicker()
        observeSelectedDate()

        // Info icon click listener
        binding.dateInfoIcon.setOnClickListener {
            createWalletCreationDateInfoDialog().show(supportFragmentManager, "wallet_creation_date_info")
        }
    }

    private fun setupDatePicker() {
        binding.selectDateButton.setOnClickListener {
            showDatePickerDialog()
        }

        binding.selectedDateDisplay.setOnClickListener {
            // Click to clear date
            viewModel.clearWalletCreationDate()
        }
    }

    private fun showDatePickerDialog() {
        val minDate = de.schildbach.wallet.Constants.EARLIEST_HD_SEED_CREATION_TIME * 1000L
        val maxDate = System.currentTimeMillis()

        // Set initial date to today
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val day = calendar.get(Calendar.DAY_OF_MONTH)

        val datePickerDialog = DatePickerDialog(
            this,
            { _, selectedYear, selectedMonth, selectedDay ->
                // User selected a date, convert to timestamp
                val selectedCalendar = Calendar.getInstance()
                selectedCalendar.set(selectedYear, selectedMonth, selectedDay, 0, 0, 0)
                selectedCalendar.set(Calendar.MILLISECOND, 0)
                viewModel.setWalletCreationDate(selectedCalendar.time.time)
            },
            year,
            month,
            day
        )

        // Set date constraints
        datePickerDialog.datePicker.minDate = minDate
        datePickerDialog.datePicker.maxDate = maxDate
        datePickerDialog.setTitle(getString(R.string.restore_wallet_date_picker_title))

        datePickerDialog.show()
    }

    private fun observeSelectedDate() {
        viewModel.selectedCreationDate.observe(this) { timestampInSeconds ->
            if (timestampInSeconds != null) {
                val dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
                val dateStr = dateFormat.format(Date(timestampInSeconds * 1000L))
                binding.selectedDateDisplay.text = getString(R.string.restore_wallet_selected_date, dateStr)
                binding.selectedDateDisplay.visibility = View.VISIBLE
                binding.selectDateButton.text = getString(R.string.fiat_balance_with_currency, getString(R.string.restore_wallet_select_date), "✓")
            } else {
                binding.selectedDateDisplay.visibility = View.GONE
                binding.selectDateButton.text = getString(R.string.restore_wallet_select_date)
            }
        }
    }

    private suspend fun restoreWallet(words: ArrayList<String>) {
        if (recoveryPinMode) {
            val recoveryData = viewModel.recoverPin(words)

            if (recoveryData != null) {
                if (!recoveryData.requiresReset) {
                    startActivity(SetPinActivity.createIntent(this, R.string.set_pin_set_pin, true, recoveryData.pin))
                } else {
                    viewModel.restoreWalletFromSeed(words, onboarding = false, requireBlockchainReset = true)
                }
            } else {
                showErrorDialog(getString(R.string.forgot_pin_passphrase_doesnt_match))
            }
        } else {
            if (viewModel.restoreWalletFromSeed(words, onboarding = true, requireBlockchainReset = false)) {
                startActivityForResult(
                    SetPinActivity.createIntent(
                        walletApplication,
                        R.string.set_pin_restore_wallet,
                        onboarding = true,
                        onboardingPath = OnboardingPath.RestoreSeed
                    ),
                    SET_PIN_REQUEST_CODE
                )
            } else {
                showErrorDialog(getString(R.string.restore_wallet_from_invalid_seed_failure, getString(R.string.error)))
            }
        }
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
        AdaptiveDialog.create(
            null,
            title = getString(R.string.import_export_keys_dialog_failure_title),
            message = errorMessage,
            negativeButtonText = "",
            positiveButtonText = getString(R.string.button_ok)
        ).show(this)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SET_PIN_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            setResult(Activity.RESULT_OK)
            finish()
        }
    }
}
