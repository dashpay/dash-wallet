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

import android.content.Context
import android.content.Intent
import android.os.Bundle
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_view_seed.*

/**
 * @author Eric Britten
 */
class ViewSeedActivity : BaseMenuActivity() {

    companion object {

        private const val EXTRA_SEED = "extra_seed"

        fun createIntent(context: Context, seed: Array<String>): Intent {
            val intent = Intent(context, ViewSeedActivity::class.java)
            intent.putExtra(EXTRA_SEED, seed)
            return intent
        }
    }

    override fun getLayoutId(): Int {
        return R.layout.activity_view_seed
    }

    private var seed: Array<String> = arrayOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //setContentView(R.layout.activity_verify_seed)

        seed = if (intent.extras?.containsKey(EXTRA_SEED)!!) {
            intent.extras!!.getStringArray(EXTRA_SEED)!!
        } else {
            throw IllegalStateException("This activity needs to receive a String[] Intent Extra " +
                    "containing the recovery seed.")
        }

        val sb = seed.joinToString(" ")

        recovery_seed.text = sb.trim()

        confirm_btn.setOnClickListener {
           finish()
        }

        setTitle(R.string.view_seed_title)
    }

    override fun onBackPressed() {
        finish()
    }

    //override fun finish() {
    //    super.finish()
    //    overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    //}
}