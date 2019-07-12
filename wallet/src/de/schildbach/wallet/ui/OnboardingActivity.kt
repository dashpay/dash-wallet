package de.schildbach.wallet.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Handler
import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet_test.R
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.ui.DialogBuilder


class OnboardingActivity : AppCompatActivity() {

    companion object {
        const val DIALOG_RESTORE_WALLET_PERMISSION = 1

        const val DIALOG_RESTORE_WALLET = 2

        const val REQUEST_CODE_RESTORE_WALLET = 1
    }

    private lateinit var viewModel: OnboardingViewModel

    private lateinit var walletApplication: WalletApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        setContentView(R.layout.activity_onboarding)

        initViewModel()

        walletApplication = (application as WalletApplication)
        if (walletApplication.walletFileExists()) {
            launchWallet()
        } else {
            onboarding()
        }
    }

    @SuppressLint("StringFormatInvalid")
    private fun initViewModel() {
        viewModel = ViewModelProviders.of(this).get(OnboardingViewModel::class.java)
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
        viewModel.launchWalletAction.observe(this, Observer {
            startWalletActivity()
        })
    }

    private fun launchWallet() {
        startWalletActivity()
    }

    private fun onboarding() {
        initView()
        showButtonsDelayed()
    }

    private fun initView() {
        findViewById<Button>(R.id.create_new_wallet).setOnClickListener {
            viewModel.createNewWallet()
        }
        findViewById<Button>(R.id.recovery_wallet).setOnClickListener {
            viewModel.initBasicWalletStuffIfNeeded()
            RestoreWalletFromSeedDialogFragment.show(supportFragmentManager)
        }
        findViewById<Button>(R.id.restore_wallet).setOnClickListener {
            viewModel.initBasicWalletStuffIfNeeded()
            restoreWalletFromFile()
        }
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

    private fun restoreWalletFromFile() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            showDialog(DIALOG_RESTORE_WALLET)
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQUEST_CODE_RESTORE_WALLET)
        }
    }

    private fun startWalletActivity() {
        startActivity(Intent(this, WalletActivity::class.java))
        finish()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQUEST_CODE_RESTORE_WALLET) {
            when {
                grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED -> restoreWalletFromFile()
                else -> showDialog(DIALOG_RESTORE_WALLET_PERMISSION)
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    override fun onCreateDialog(id: Int): Dialog {
        return when (id) {
            DIALOG_RESTORE_WALLET_PERMISSION -> createRestoreWalletPermissionDialog()
            DIALOG_RESTORE_WALLET -> createRestoreWalletDialog()
            else -> super.onCreateDialog(id)
        }
    }

    private fun createRestoreWalletPermissionDialog(): Dialog {
        return RestoreFromFileHelper.createRestoreWalletPermissionDialog(this)
    }

    private fun createRestoreWalletDialog(): Dialog {
        return RestoreFromFileHelper.createRestoreWalletDialog(this, object : RestoreFromFileHelper.OnRestoreWalletListener {
            override fun onRestoreWallet(wallet: Wallet) {
                viewModel.restoreWalletFromFile(wallet)
            }

            override fun onRetryRequest() {
                showDialog(DIALOG_RESTORE_WALLET)
            }
        })
    }

    override fun onPrepareDialog(id: Int, dialog: Dialog?) {
        when (id) {
            DIALOG_RESTORE_WALLET -> RestoreFromFileHelper.prepareRestoreWalletDialog(this, false, dialog)
            else -> super.onPrepareDialog(id, dialog)
        }
    }
}
