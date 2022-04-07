/*
 * Copyright 2021 Dash Core Group.
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

package de.schildbach.wallet.ui.explore

import android.os.Bundle
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.BaseMenuActivity
import de.schildbach.wallet_test.R
import org.dash.wallet.features.exploredash.ui.ExploreViewModel
import org.dash.wallet.features.exploredash.ui.NavigationRequest

@AndroidEntryPoint
class ExploreActivity : BaseMenuActivity() {
    private val viewModel: ExploreViewModel by viewModels()

    override fun getLayoutId(): Int {
        return R.layout.activity_explore
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel.navigationCallback.observe(this) { request ->
            when (request) {
                NavigationRequest.SendDash -> {
//                    val sendCoinsIntent = PaymentsActivity.createIntent(this, 0) // TODO
//                    startActivity(sendCoinsIntent)
                }
                NavigationRequest.ReceiveDash -> {
//                    val sendCoinsIntent = PaymentsActivity.createIntent(this, 1)
//                    startActivity(sendCoinsIntent)
                }
                else -> {}
            }
        }
    }
}