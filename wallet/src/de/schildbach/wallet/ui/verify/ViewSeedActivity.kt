/*
 * Copyright 2023 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui.verify

import android.content.Context
import android.content.Intent
import android.os.Bundle
import de.schildbach.wallet.ui.BaseMenuActivity
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
        super.setSecuredActivity(true)

        seed = if (intent.extras?.containsKey(EXTRA_SEED)!!) {
            intent.extras!!.getStringArray(EXTRA_SEED)!!
        } else {
            throw IllegalStateException(
                "This activity needs to receive a String[] Intent Extra " +
                    "containing the recovery seed."
            )
        }

        val sb = seed.joinToString(" ")

        recovery_seed.text = sb.trim()

        confirm_btn.setOnClickListener {
            finish()
        }

        explanation_btn.setOnClickListener {
            VerifySeedWarningDialog().show(supportFragmentManager, "verify_seed_warning")
        }

        setTitle(R.string.view_seed_title)
    }

    override fun onBackPressed() {
        finish()
    }

    override fun onLockScreenActivated() {
        finish()
    }
}
