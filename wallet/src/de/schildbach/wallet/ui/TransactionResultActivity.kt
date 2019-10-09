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
import android.graphics.drawable.Animatable
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.TransactionResult
import de.schildbach.wallet.util.WalletUtils
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_successful_transaction.*

/**
 * @author Samuel Barbosa
 */
class TransactionResultActivity : AppCompatActivity() {

    private lateinit var transactionResult: TransactionResult

    companion object {
        const val TRANSACTION_RESULT_EXTRA = "transaction_result_extra"
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        transactionResult = intent.getSerializableExtra(TRANSACTION_RESULT_EXTRA) as TransactionResult

        setContentView(R.layout.activity_successful_transaction)

        view_on_explorer.setOnClickListener { viewOnExplorer(transactionResult.transactionHash) }
        transaction_close_btn.setOnClickListener {
            val intent = Intent(this, WalletActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
        }

        val noCodeFormat = WalletApplication.getInstance().configuration.format.noCode()

        dash_amount.setFormat(noCodeFormat)
        //For displaying purposes only
        if (transactionResult.dashAmount.isNegative) {
            dash_amount.setAmount(transactionResult.dashAmount.negate())
        } else {
            dash_amount.setAmount(transactionResult.dashAmount)
        }

        transaction_fee.setFormat(noCodeFormat)
        transaction_fee.setAmount(transactionResult.feeAmount)

        transaction_address.text = WalletUtils.buildShortAddress(transactionResult.address)

        val exchangeRate = transactionResult.exchangeRate
        val exchangeCurrencyCode = WalletApplication.getInstance().configuration
                .exchangeCurrencyCode
        fiat_value.setFiatAmount(transactionResult.dashAmount, exchangeRate, Constants.LOCAL_FORMAT,
                exchangeCurrencyCode)

        check_icon.postDelayed({
            check_icon.visibility = View.VISIBLE
            (check_icon.drawable as Animatable).start()
        }, 400)
    }

    private fun viewOnExplorer(txHash: String) {
        val config = WalletApplication.getInstance().configuration
        startActivity(Intent(Intent.ACTION_VIEW,
                Uri.withAppendedPath(config.blockExplorer, "tx/$txHash")))
    }

}