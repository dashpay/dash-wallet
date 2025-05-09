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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.dash.wallet.common.data.WalletUIConfig
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShortcutProvider @Inject constructor(
    private val config: WalletUIConfig
) {
    companion object {
        private const val MINIMUM_SHORTCUTS = 4
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _customShortcuts = MutableStateFlow<List<ShortcutOption>>(emptyList())
    val customShortcuts: StateFlow<List<ShortcutOption>> = _customShortcuts.asStateFlow()

    init {
        config.observe(WalletUIConfig.CUSTOMIZED_SHORTCUTS)
            .filterNotNull()
            .distinctUntilChanged()
            .map { shortcutSet -> parseCustomShortcuts(shortcutSet) }
            .onEach { shortcuts ->
                var finalShortcuts = shortcuts.toMutableList()

                if (finalShortcuts.size < MINIMUM_SHORTCUTS) {
                    val allShortcuts = ShortcutOption.entries
                    allShortcuts
                        .firstOrNull { it != ShortcutOption.SECURE_NOW && it !in finalShortcuts }
                        // Most likely short 1 item due to removal from the start of the list
                        ?.let { finalShortcuts.add(0, it) }
                    setCustomShortcuts(finalShortcuts.map { it.id }.toIntArray()) // This will trigger another pass
                } else {
                    _customShortcuts.value = finalShortcuts
                }
            }
            .launchIn(scope)
    }

    // Default logic before the user customizes shortcuts
    fun getFilteredShortcuts(
        isPassphraseVerified: Boolean = true,
        userHasBalance: Boolean = true,
        userHasContacts: Boolean = false
    ): List<ShortcutOption> {
        val shortcuts = ShortcutOption.entries.filter { shortcut ->
            when (shortcut) {
                ShortcutOption.SECURE_NOW -> !isPassphraseVerified
                ShortcutOption.SCAN_QR -> userHasBalance
                ShortcutOption.SEND -> !userHasBalance && isPassphraseVerified
                ShortcutOption.BUY_SELL -> !userHasBalance
                ShortcutOption.SEND_TO_ADDRESS -> userHasBalance
                ShortcutOption.SEND_TO_CONTACT -> userHasBalance && userHasContacts
                else -> true
            }
        }

        return shortcuts
    }
    
    suspend fun setCustomShortcuts(shortcutIds: IntArray) {
        val shortcutString = shortcutIds.joinToString(",") { it.toString() }
        config.set(WalletUIConfig.CUSTOMIZED_SHORTCUTS, shortcutString)
    }

    private fun parseCustomShortcuts(shortcutString: String): List<ShortcutOption> {
        return shortcutString.split(",").mapNotNull { idStr ->
            val id = idStr.toIntOrNull() ?: return@mapNotNull null
            ShortcutOption.fromId(id)
        }
    }
} 