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

import android.os.Bundle
import android.view.View
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_advanced_security.*

class AdvancedSecurityActivity : BaseMenuActivity() {
    override fun getLayoutId(): Int {
        return R.layout.activity_advanced_security
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        autoLogoutSwitch.setOnCheckedChangeListener {_, enabled ->
            autoLogoutPanel.visibility = if (enabled) View.VISIBLE  else View.GONE
        }

        spendingConfirmationSwitch.setOnCheckedChangeListener {_, enabled ->
            spendingConfirmationPanel.visibility = if (enabled) View.VISIBLE  else View.GONE
        }

        setTitle(R.string.security_title)
    }

    private fun updateView() {

    }
}
