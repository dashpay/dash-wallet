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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.service.DeviceInfoProvider
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.integrations.coinbase.repository.CoinBaseRepositoryInt
import org.dash.wallet.integrations.uphold.api.TopperClient
import javax.inject.Inject

@HiltViewModel
class ShortcutsViewModel @Inject constructor(
    private val walletUIConfig: WalletUIConfig,
    private val config: Configuration,
    private val walletData: WalletDataProvider,
    private val shortcutProvider: ShortcutProvider,
    private val deviceInfo: DeviceInfoProvider,
    private val topperClient: TopperClient,
    private val coinBaseRepository: CoinBaseRepositoryInt
): ViewModel() {
    private val maxShortcuts = if (deviceInfo.isSmallScreen) 3 else 4
    private var isPassphraseVerified = true
    private val hasCustomShortcuts: Boolean
        get() = shortcutProvider.customShortcuts.value.isNotEmpty()

    private var _userHasBalance = true
    var userHasBalance: Boolean
        get() = _userHasBalance
        set(value) {
            _userHasBalance = value
            
            if (!hasCustomShortcuts) {
                shortcuts = getPresetShortcuts().take(maxShortcuts)
            }
        }

    private var _userHasContacts = true
    var userHasContacts: Boolean
        get() = _userHasContacts
        set(value) {
            _userHasContacts = value
            
            if (!hasCustomShortcuts) {
                shortcuts = getPresetShortcuts().take(maxShortcuts)
            }
        }

    var shortcuts by mutableStateOf(getPresetShortcuts().take(maxShortcuts))

    val isCoinbaseAuthenticated: Boolean
        get() = coinBaseRepository.isAuthenticated

    init {
        shortcutProvider.customShortcuts
            .filterNot { it.isEmpty() }
            .onEach { shortcuts = it.take(maxShortcuts) }
            .launchIn(viewModelScope)
    }

    fun getAllShortcutOptions(replacingShortcut: ShortcutOption): List<ShortcutOption> {
        return ShortcutOption.entries.filterNot { it == replacingShortcut || it == ShortcutOption.SECURE_NOW }
    }

    fun refreshIsPassphraseVerified() {
        isPassphraseVerified = !config.remindBackupSeed

        if (isPassphraseVerified && shortcuts.contains(ShortcutOption.SECURE_NOW)) {
            if (hasCustomShortcuts) {
                removeSecureNowShortcut()
            } else {
                shortcuts = getPresetShortcuts().take(maxShortcuts)
            }
        }
    }

    fun replaceShortcut(oldIndex: Int, new: ShortcutOption) {
        if (oldIndex !in shortcuts.indices) {
            return
        }

        val currentShortcuts = shortcuts.toMutableList()

        if (currentShortcuts[oldIndex] == ShortcutOption.SECURE_NOW) {
            // Don't allow replacing SECURE_NOW shortcut
            return
        }

        currentShortcuts[oldIndex] = new
        val shortcutIds = currentShortcuts.map { it.id }
            .take(maxShortcuts)
            .toIntArray()
        viewModelScope.launch {
            shortcutProvider.setCustomShortcuts(shortcutIds)
        }
    }

    private fun removeSecureNowShortcut() {
        val currentShortcuts = shortcuts.toMutableList()
        val index = currentShortcuts.indexOf(ShortcutOption.SECURE_NOW)
        currentShortcuts.removeAt(index)
        val shortcutIds = currentShortcuts.map { it.id }
            .take(maxShortcuts)
            .toIntArray()
        viewModelScope.launch {
            shortcutProvider.setCustomShortcuts(shortcutIds)
        }
    }

    private fun getPresetShortcuts(): List<ShortcutOption> {
        return shortcutProvider.getFilteredShortcuts(
            isPassphraseVerified = isPassphraseVerified,
            userHasBalance = userHasBalance,
            userHasContacts = userHasContacts
        )
    }

    suspend fun getTopperUrl(walletName: String): String {
        return topperClient.getOnRampUrl(
            walletUIConfig.getExchangeCurrencyCode(),
            walletData.freshReceiveAddress(),
            walletName
        )
    }
}