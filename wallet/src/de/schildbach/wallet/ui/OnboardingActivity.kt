package de.schildbach.wallet.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet_test.R

class OnboardingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        setContentView(R.layout.activity_onboarding)
        showButtonsDelayed()
        initView()
    }

    private fun showButtonsDelayed() {
        Handler().postDelayed({
            findViewById<LinearLayout>(R.id.buttons).visibility = View.VISIBLE
        }, 1000)
    }

    private fun initView() {
        findViewById<Button>(R.id.create_new_wallet).setOnClickListener {
            (application as WalletApplication).initStuff();
            startActivity(Intent(this, WalletActivity::class.java))
            finish()
        }
        findViewById<Button>(R.id.recovery_wallet).setOnClickListener {

        }
    }
}
