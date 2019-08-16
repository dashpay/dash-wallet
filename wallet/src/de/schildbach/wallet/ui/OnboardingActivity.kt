package de.schildbach.wallet.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Handler
import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.DialogBuilder


class OnboardingActivity : RestoreFromFileActivity() {

    private lateinit var viewModel: OnboardingViewModel

    private lateinit var walletApplication: WalletApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        setContentView(R.layout.activity_onboarding)

        viewModel = ViewModelProviders.of(this).get(OnboardingViewModel::class.java)

        walletApplication = (application as WalletApplication)
        if (walletApplication.walletFileExists()) {
            regularFlow()
        } else {
            if (walletApplication.wallet == null) {
                onboarding()
            } else {
                if (walletApplication.wallet.isEncrypted) {
                    walletApplication.fullInitialization()
                    regularFlow()
                } else {
                    startActivity(SetPinActivity.createIntent(this, R.string.set_pin_create_new_wallet))
                }
            }
        }
    }

    private fun regularFlow() {
        try {
            startActivity(Intent(this, WalletActivity::class.java))
            finish()
        } catch (x: Exception) {
            findViewById<View>(R.id.fatal_error_message).visibility = View.VISIBLE
        }
    }

    private fun onboarding() {
        initView()
        initViewModel()
        showButtonsDelayed()
    }

    private fun initView() {
        findViewById<Button>(R.id.create_new_wallet).setOnClickListener {
            viewModel.createNewWallet()
        }
        findViewById<Button>(R.id.recovery_wallet).setOnClickListener {
            walletApplication.initEnvironmentIfNeeded()
            RestoreWalletFromSeedDialogFragment.show(supportFragmentManager)
        }
        findViewById<Button>(R.id.restore_wallet).setOnClickListener {
            restoreWalletFromFile()
        }
    }

    @SuppressLint("StringFormatInvalid")
    private fun initViewModel() {
        viewModel.showToastAction.observe(this, Observer {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
        })
        viewModel.showRestoreWalletFailureAction.observe(this, Observer {
            val message = when {
                TextUtils.isEmpty(it.message) -> it.javaClass.simpleName
                else -> it.message!!
            }
            val dialog = DialogBuilder.warn(this, R.string.import_export_keys_dialog_failure_title)
            dialog.setMessage(getString(R.string.import_keys_dialog_failure, message))
            dialog.setPositiveButton(R.string.button_dismiss, null)
            dialog.setNegativeButton(R.string.button_retry) { _, _ ->
                RestoreWalletFromSeedDialogFragment.show(supportFragmentManager)
            }
            dialog.show()
        })
        viewModel.startActivityAction.observe(this, Observer {
            startActivity(it)
        })
    }

    private fun showButtonsDelayed() {
        val sloganDrawable = (window.decorView.background as LayerDrawable).getDrawable(1)
        sloganDrawable.mutate().alpha = 0
        Handler().postDelayed({
            findViewById<LinearLayout>(R.id.buttons).visibility = View.VISIBLE
        }, 1000)
    }

    fun restoreWalletFromSeed(words: MutableList<String>) {
        viewModel.restoreWalletFromSeed(words)
    }

    override fun startActivity(intent: Intent?) {
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        super.startActivity(intent)
    }
}
