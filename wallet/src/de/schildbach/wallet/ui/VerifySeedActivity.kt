package de.schildbach.wallet.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import de.schildbach.wallet_test.R
import java.lang.IllegalStateException

/**
 * @author Samuel Barbosa
 */
class VerifySeedActivity : AppCompatActivity(), VerifySeedActions {

    private var seed: Array<String> = arrayOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_verify_seed)

        seed = if (intent.extras?.containsKey("seed")!!) {
            intent.extras.getStringArray("seed")
        } else {
            throw IllegalStateException("This activity needs to receive a String[] containing " +
                    "the recovery seed")
        }
        val verifySeedWriteDownFragment = VerifySeedWriteDownFragment.newInstance(seed)
        supportFragmentManager.beginTransaction().add(R.id.container,
                verifySeedWriteDownFragment).commit()
    }

    override fun onVerifyWriteDown() {
        supportFragmentManager.beginTransaction().replace(R.id.container,
                VerifySeedConfirmFragment.newInstance(seed)).commit()
    }

    override fun onSeedVerified() {
        startActivity(Intent(this, WalletActivity::class.java))
        finish()
    }

}