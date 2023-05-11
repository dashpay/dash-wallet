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

package de.schildbach.wallet.transactions

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.bitcoinj.utils.Threading
import org.bitcoinj.wallet.authentication.AuthenticationGroupExtension
import org.bitcoinj.wallet.authentication.AuthenticationKeyUsage
import org.bitcoinj.wallet.listeners.AuthenticationKeyUsageEventListener

class MasternodeObserver(private val authenticationGroupExtension: AuthenticationGroupExtension) {

    fun observeAuthenticationKeyUsage(): Flow<List<AuthenticationKeyUsage>> = callbackFlow {
        val usageListener = AuthenticationKeyUsageEventListener {
            trySend(it)
        }
        authenticationGroupExtension.addAuthenticationKeyUsageEventListener(Threading.USER_THREAD, usageListener)

        awaitClose {
            authenticationGroupExtension.removeAuthenticationKeyUsageEventListener(usageListener)
        }
    }
}