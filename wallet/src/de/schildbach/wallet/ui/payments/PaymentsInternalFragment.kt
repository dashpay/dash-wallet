/*
 * Copyright 2026 Dash Core Group.
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

package de.schildbach.wallet.ui.payments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.shielded.ShieldedBalanceActivity
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.components.Menu
import org.dash.wallet.common.ui.components.MenuItem
import org.dash.wallet.common.ui.components.MyTheme

/**
 * "Internal" tab of the payments screen (Figma 1684:13169): the
 * "Internal transfer to/from → Shielded balance" row, opening the
 * shielded internal-transfer flow. Only instantiated when
 * `SUPPORTS_PLATFORM` and `USE_KOTLIN_SDK_SHIELDED` are both on.
 */
@AndroidEntryPoint
class PaymentsInternalFragment : Fragment() {

    companion object {
        @JvmStatic
        fun newInstance() = PaymentsInternalFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 15.dp)
                    .padding(top = 15.dp)
            ) {
                Menu {
                    Text(
                        text = stringResource(R.string.shielded_internal_transfer_to_from),
                        style = MyTheme.Typography.BodyMedium,
                        color = MyTheme.Colors.textTertiary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                    MenuItem(
                        title = stringResource(R.string.shielded_balance_title),
                        icon = R.drawable.ic_shielded_balance,
                        action = {
                            startActivity(ShieldedBalanceActivity.createIntent(requireContext()))
                        }
                    )
                }
            }
        }
    }
}
