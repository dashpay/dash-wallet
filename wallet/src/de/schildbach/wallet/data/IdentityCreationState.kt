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
class IdentityCreationState(var state: State, var username: String) {

    @PrimaryKey
    var id = 1
        set(value) {
            field = 1
        }

    enum class State {
        PROCESSING_PAYMENT, CREATING_IDENTITY, REGISTERING_USERNAME, DONE
    }

    fun nextState() {
        state = when (state) {
            State.PROCESSING_PAYMENT -> State.CREATING_IDENTITY
            State.CREATING_IDENTITY -> State.REGISTERING_USERNAME
            State.REGISTERING_USERNAME -> State.DONE
            else -> State.PROCESSING_PAYMENT
        }
    }

}