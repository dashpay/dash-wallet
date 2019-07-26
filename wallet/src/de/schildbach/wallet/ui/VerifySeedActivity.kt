package de.schildbach.wallet.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.verify_seed.*

/**
 * @author Samuel Barbosa
 */
class VerifySeedActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.verify_seed)

        recovery_seed.text = "network   stand   grid   bundle   need   eight   blast   topic   depth   right   desk   faith"
        written_down_checkbox.setOnCheckedChangeListener { buttonView, isChecked ->
            confirm_written_down_btn.isEnabled = isChecked
        }
    }

}