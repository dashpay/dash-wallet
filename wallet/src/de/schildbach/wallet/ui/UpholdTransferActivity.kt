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

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.res.ResourcesCompat
import androidx.core.text.toSpannable
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import de.schildbach.wallet.ui.send.EnterAmountSharedViewModel
import de.schildbach.wallet_test.R
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.MonetaryFormat

class UpholdTransferActivity : AppCompatActivity() {

    companion object {

        private const val EXTRA_MAX_AMOUNT = "extra_max_amount"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_MESSAGE = "extra_message"

        private const val RESULT_AMOUNT = "result_amount"

        fun createIntent(context: Context, title: String, message: CharSequence, maxAmount: String): Intent {
            val intent = Intent(context, UpholdTransferActivity::class.java)
            intent.putExtra(EXTRA_TITLE, title)
            intent.putExtra(EXTRA_MESSAGE, message)
            intent.putExtra(EXTRA_MAX_AMOUNT, maxAmount)
            return intent
        }
    }

    private lateinit var enterAmountSharedViewModel: EnterAmountSharedViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_uphold_tranfser)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                    .replace(R.id.container, EnterAmountFragment.newInstance(Coin.ZERO))
                    .commitNow()
        }

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        val actionBar = supportActionBar
        actionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }

        title = intent.getStringExtra(EXTRA_TITLE)

        enterAmountSharedViewModel = ViewModelProviders.of(this).get(EnterAmountSharedViewModel::class.java)
        enterAmountSharedViewModel.buttonTextData.call(R.string.uphold_transfer)

        val balanceStr = intent.getStringExtra(EXTRA_MAX_AMOUNT)
        val available = Coin.parseCoin(balanceStr)

        val drawableDash = ResourcesCompat.getDrawable(resources, R.drawable.ic_dash_d_black, null)
        drawableDash!!.setBounds(0, 0, 38, 38)
        val dashSymbol = ImageSpan(drawableDash, ImageSpan.ALIGN_BASELINE)
        val builder = SpannableStringBuilder()
        builder.appendln(intent.getStringExtra(EXTRA_MESSAGE))
        builder.append("  ")
        builder.setSpan(dashSymbol, builder.length - 2, builder.length - 1, 0)
        val dashFormat = MonetaryFormat().noCode().minDecimals(6).optionalDecimals()
        builder.append(dashFormat.format(available))
        builder.append("  ")
        builder.append(getText(R.string.enter_amount_available))

        enterAmountSharedViewModel.messageTextStringData.value = builder.toSpannable()
        enterAmountSharedViewModel.buttonClickEvent.observe(this, Observer {
            val dashAmount = enterAmountSharedViewModel.dashAmount
            Intent().run {
                putExtra(RESULT_AMOUNT, dashAmount.longValue())
                setResult(Activity.RESULT_OK, this)
            }
            finish()
        })
        enterAmountSharedViewModel.maxButtonVisibleData.value = true
        enterAmountSharedViewModel.maxButtonClickEvent.observe(this, Observer<Boolean?> {
            enterAmountSharedViewModel.applyMaxAmountEvent.setValue(available)
        })
        enterAmountSharedViewModel.dashAmountData.observe(this, Observer<Coin> {
            enterAmountSharedViewModel.buttonEnabledData.setValue(it.isPositive)
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
}
