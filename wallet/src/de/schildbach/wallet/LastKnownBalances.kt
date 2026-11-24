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

import org.bitcoinj.wallet.Wallet

/**
 * The id the stored last-known balances are tagged with
 * ([org.dash.wallet.common.data.lastKnownBalance]): the hash of this wallet's watching key, in
 * hex. Public data only, so it can be read without the PIN, and the same for every restore
 * of the same recovery phrase.
 */
fun Wallet.balanceOwnerId(): String = watchingKey.pubKeyHash.joinToString("") { "%02x".format(it) }
