/*
 * Copyright 2020 Dash Core Group.
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

package de.schildbach.wallet.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "identity_creation_state")
class IdentityCreationState(var state: State, var error: Boolean, var username: String) {

    @PrimaryKey
    var id = 1
        set(value) {
            field = 1
        }

    enum class State {
        UPGRADING_WALLET,
        CREDIT_FUNDING_TX_CREATING,
        CREDIT_FUNDING_TX_SENDING,
        CREDIT_FUNDING_TX_SENT,
        CREDIT_FUNDING_TX_CONFIRMED,
        IDENTITY_REGISTERING,
        IDENTITY_REGISTERED,
        PREORDER_REGISTERING,
        PREORDER_REGISTERED,
        USERNAME_REGISTERING,
        USERNAME_REGISTERED,
        DASHPAY_PROFILE_CREATING,
        DASHPAY_PROFILE_CREATED
    }

    enum class Error {
        CONNECTION,
        TIMEOUT,
        ALREADY_EXISTS,
        INVALID_QUERY,
        INVALID_TRANSITION
    }
}