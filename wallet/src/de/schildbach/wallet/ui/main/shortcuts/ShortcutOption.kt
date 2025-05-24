/*
 * Copyright 2025 Dash Core Group.
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
package de.schildbach.wallet.ui.main.shortcuts

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import de.schildbach.wallet_test.R

// Sorted by display priority. Do not change IDs if moving options around
enum class ShortcutOption(
    val id: Int,
    @DrawableRes val iconResId: Int,
    @StringRes val textResId: Int
) {
    SECURE_NOW(
        0,
        R.drawable.ic_shortcut_secure_now,
        R.string.shortcut_secure_now
    ),
    EXPLORE(
        1,
        R.drawable.ic_explore,
        R.string.menu_explore_title
    ),
    RECEIVE(
        2,
        R.drawable.ic_transaction_received,
        R.string.shortcut_receive
    ),
    SEND(
        3,
        R.drawable.ic_transaction_sent,
        R.string.shortcut_send
    ),
    SCAN_QR(
        4,
        R.drawable.ic_qr,
        R.string.shortcut_scan_to_pay
    ),
    SEND_TO_ADDRESS(
        5,
        R.drawable.ic_send_to_address,
        R.string.send_to_address
    ),
    SEND_TO_CONTACT(
        6,
        R.drawable.ic_contact,
        R.string.shortcut_pay_to_contact
    ),
    BUY_SELL(
        7,
        R.drawable.ic_shortcut_buy_sell_dash,
        R.string.shortcut_buy_sell
    ),
    WHERE_TO_SPEND(
        8,
        R.drawable.ic_where_to_spend,
        R.string.explore_where_to_spend
    ),
    ATMS(
        9,
        R.drawable.ic_shortcut_atm,
        R.string.explore_atms
    ),
    STAKING(
        10,
        R.drawable.ic_shortcut_staking,
        R.string.staking_title
    ),
    TOPPER(
        11,
        R.drawable.logo_topper,
        R.string.topper
    ),
    UPHOLD(
        12,
        R.drawable.ic_uphold,
        R.string.uphold_account
    ),
    COINBASE(
        13,
        R.drawable.ic_coinbase,
        R.string.coinbase
    );

    companion object  {
        fun fromId(id: Int): ShortcutOption {
            return entries.first { it.id == id }
        }
    }
}