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
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.data.BaseConfig
import org.dash.wallet.common.data.WalletUIConfig
import org.slf4j.LoggerFactory
import java.io.IOException

/**
 * Removes the persisted last-known balances ([WalletUIConfig.LAST_TOTAL_BALANCE] and
 * [DashPayConfig.LAST_SHIELDED_BALANCE_DUFFS]) and their ownership flags. They carry no wallet
 * identity, so they are cleared whenever a wallet is installed (and, with every other setting,
 * by Reset Wallet): a stored balance then always belongs to the installed wallet. Without this,
 * a wallet restored from the recovery screen, which installs without a reset, would inherit the
 * previous wallet's values on the home header and in the inactivity reminder.
 *
 * Each writer sets the value's flag ([WalletUIConfig.LAST_TOTAL_BALANCE_OWNED],
 * [DashPayConfig.LAST_SHIELDED_BALANCE_OWNED]) with it. A value an earlier version stored has
 * no flag, so it may belong to a previous wallet: [ownedBalance] keeps it out of the reminder
 * until the installed wallet writes its own. Nothing is deleted for that.
 *
 * Each flag is removed before its value: a clear stopped part way leaves a value without a
 * flag (unknown), never a flag on a value the clear meant to remove.
 *
 * A store that cannot be read at all (a corrupt file) cannot be edited either, but nothing can
 * read a balance from it, so it counts as cleared: a corrupt display cache must not block
 * every wallet install for good.
 *
 * Blocks: the caller is on a background thread (every install path is). Throws when a store
 * can still be read but cannot be cleared; the caller must not install the wallet then.
 */
@Throws(IOException::class)
fun clearLastKnownBalances(walletUIConfig: WalletUIConfig, dashPayConfig: DashPayConfig) = runBlocking {
    clearOrUnreadable(walletUIConfig) {
        walletUIConfig.remove(WalletUIConfig.LAST_TOTAL_BALANCE_OWNED)
        walletUIConfig.remove(WalletUIConfig.LAST_TOTAL_BALANCE)
    }
    clearOrUnreadable(dashPayConfig) {
        dashPayConfig.remove(DashPayConfig.LAST_SHIELDED_BALANCE_OWNED)
        dashPayConfig.remove(DashPayConfig.LAST_SHIELDED_BALANCE_DUFFS)
    }
}

/** Runs [clear]; on failure, rethrows unless [config] cannot be read either. */
private suspend fun clearOrUnreadable(config: BaseConfig, clear: suspend () -> Unit) {
    try {
        clear()
    } catch (e: IOException) {
        val readable = try {
            config.snapshotPreservingErrors()
            true
        } catch (unreadable: IOException) {
            false
        }
        if (readable) throw e
        log.warn("{} cannot be read or written; its stored balances are unreadable, so treated as cleared",
            config.preferencesFileName, e)
    }
}

private val log = LoggerFactory.getLogger("LastKnownBalances")

/**
 * The stored [value] when its ownership [flag] is set; null (unknown) otherwise. Both from this
 * one snapshot: two separate reads could pair an earlier version's value with a flag the
 * installed wallet's writer set in between.
 */
fun Preferences.ownedBalance(value: Preferences.Key<Long>, flag: Preferences.Key<Boolean>): Long? =
    this[value].takeIf { this[flag] == true }
