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

package de.schildbach.wallet.ui.username

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@HiltViewModel
// TODO: this viewModel might not be needed once the logic is implemented.
class VotingKeyViewModel @Inject constructor(): ViewModel() {
    private val validKeys = listOf(
        "kn2GwaSZkoY8qg6i2dPCpDtDoBCftJWMzZXtHDDJ1w7PjFYfq",
        "n6YtJ7pdDYPTa57imEHEp8zinq1oNGUdwZQdnGk1MMpCWBHEq",
        "maEiRZeKXNLZovNqoS3HkmZJGmACbro7s3eC8GenExLF7QMQs"
    )

    private val _addedKeys = MutableStateFlow(listOf<String>())
    val masternodeIPs: Flow<List<String>> = _addedKeys.map {
        List(it.size) { i -> "323.232.23.$i" }
    }

    val keysAmount: Int
        get() = _addedKeys.value.size

    fun verifyKey(key: String): Boolean {
        return validKeys.contains(key)
    }

    fun addKey(key: String) {
        _addedKeys.value = _addedKeys.value + key
    }
}
