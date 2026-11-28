/*
 * Copyright 2022 Dash Core Group.
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

package org.dash.wallet.common.services

import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.Flow
import org.dash.wallet.common.data.SecuritySystemStatus

interface AuthenticationManager {
    fun authenticate(activity: FragmentActivity, pinOnly: Boolean = false, callback: (String?) -> Unit)
    suspend fun authenticate(activity: FragmentActivity, pinOnly: Boolean = false): String?
    /**
     * Sign [message] with the private key of [address], returning the
     * base64 signature.
     *
     * Throws [MessageSigningException] on every failure — implementations
     * must NOT return an empty string when the wallet cannot sign (see that
     * type's doc for why). [message] must contain no unpaired UTF-16
     * surrogate.
     */
    suspend fun signMessage(address: String, message: String): String
    fun getHealth(): SecuritySystemStatus
    fun observeHealth(): Flow<SecuritySystemStatus>
}
