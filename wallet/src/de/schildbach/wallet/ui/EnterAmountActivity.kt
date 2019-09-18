package de.schildbach.wallet.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import de.schildbach.wallet_test.R
import org.bitcoinj.core.Coin

class EnterAmountActivity : AppCompatActivity(), EnterAmountFragment.EnterAmountFragmentListener {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_enter_amount)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
//                    .replace(R.id.container, EnterAmountFragment.newInstance(Fiat.parseFiat("EUR", "1.234")))
                    .replace(R.id.container, EnterAmountFragment.newInstance(Coin.parseCoin("1.234")))
                    .commitNow()
        }
    }

    override fun onConfirmButtonClick() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}
