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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.shielded.ShieldedBalanceActivity
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.components.MyTheme

/**
 * "Internal" tab of the payments screen (Figma 1684:13169): the
 * "Internal transfer to/from → Shielded balance" card, opening the
 * shielded internal-transfer flow. Only instantiated when
 * `SUPPORTS_PLATFORM` and `USE_KOTLIN_SDK_SHIELDED` are both on.
 *
 * The card is built directly (not via the shared Menu/MenuItem, whose
 * 20dp internal margins + 56dp min row height made it narrower and
 * taller than the design): 15dp horizontal margins matching the other
 * payments tabs' cards, 6dp card padding, a 13sp header row (px 10 /
 * py 6) and one compact 30dp-icon row (p 10) — the design's menu specs.
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MyTheme.Colors.backgroundSecondary, RoundedCornerShape(20.dp))
                        .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.shielded_internal_transfer_to_from),
                        style = MyTheme.Typography.BodySmall,
                        color = MyTheme.Colors.textSecondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable {
                                startActivity(
                                    ShieldedBalanceActivity.createIntent(requireContext())
                                )
                            }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_shielded_balance),
                            contentDescription = null,
                            modifier = Modifier.size(30.dp)
                        )
                        Text(
                            text = stringResource(R.string.shielded_balance_title),
                            style = MyTheme.Typography.BodyMediumMedium,
                            color = MyTheme.Colors.textPrimary
                        )
                    }
                }
            }
        }
    }
}
