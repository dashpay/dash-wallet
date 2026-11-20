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
@file:JvmName("LastKnownBalances")

package de.schildbach.wallet

import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.data.WalletUIConfig
import java.io.IOException

/**
 * Removes the persisted last-known balances ([WalletUIConfig.LAST_TOTAL_BALANCE] and
 * [DashPayConfig.LAST_SHIELDED_BALANCE_DUFFS]). They carry no wallet identity, so they are
 * cleared whenever a wallet is installed (and, with every other setting, by Reset Wallet):
 * a stored balance then always belongs to the installed wallet. Without this, a wallet
 * restored from the recovery screen, which installs without a reset, would inherit the
 * previous wallet's values on the home header and in the inactivity reminder.
 *
 * Blocks: the caller is on a background thread (every install path is). Throws when the
 * store cannot be written; the caller must not install the wallet then.
 */
@Throws(IOException::class)
fun clearLastKnownBalances(walletUIConfig: WalletUIConfig, dashPayConfig: DashPayConfig) = runBlocking {
    clear(walletUIConfig, dashPayConfig)
}

/**
 * Makes sure the stored balances belong to the installed wallet before they are used outside
 * the app. Values an earlier version stored are not known to: before install-time clearing,
 * a wallet restored from the recovery screen kept the previous wallet's values. So the first
 * call clears them once, and the next writes come from the installed wallet. After that, and
 * after any install-time clear, it does nothing.
 *
 * Blocks; for background threads. Throws when the store cannot be read or written: the
 * caller must then treat both balances as unknown.
 */
@Throws(IOException::class)
fun ensureLastKnownBalancesOwned(walletUIConfig: WalletUIConfig, dashPayConfig: DashPayConfig) = runBlocking {
    if (walletUIConfig.getPreservingErrors(WalletUIConfig.LAST_BALANCES_OWNED) != true) {
        clear(walletUIConfig, dashPayConfig)
    }
}

/** The marker goes last: a clear stopped part way is done again next time. */
private suspend fun clear(walletUIConfig: WalletUIConfig, dashPayConfig: DashPayConfig) {
    walletUIConfig.remove(WalletUIConfig.LAST_TOTAL_BALANCE)
    dashPayConfig.remove(DashPayConfig.LAST_SHIELDED_BALANCE_DUFFS)
    walletUIConfig.set(WalletUIConfig.LAST_BALANCES_OWNED, true)
}
