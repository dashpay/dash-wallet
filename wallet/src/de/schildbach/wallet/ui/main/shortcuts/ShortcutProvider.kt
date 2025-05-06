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

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import org.dash.wallet.common.data.WalletUIConfig
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShortcutProvider @Inject constructor(
    private val config: WalletUIConfig
) {
    val customShortcuts = config.observe(WalletUIConfig.CUSTOMIZED_SHORTCUTS)
        .filterNotNull()
        .distinctUntilChanged()
        .map { shortcutSet -> parseCustomShortcuts(shortcutSet) }

    fun getAllShortcuts(): List<ShortcutOption> {
        return ShortcutOption.entries
    }
    
    private fun parseCustomShortcuts(shortcutString: String): List<ShortcutOption> {
        return shortcutString.split(",").mapNotNull { idStr -> 
            val id = idStr.toIntOrNull() ?: return@mapNotNull null
            ShortcutOption.entries.find { it.id == id }
        }
    }

    fun getFilteredShortcuts(
        isPassphraseVerified: Boolean = true,
        userHasBalance: Boolean = true,
        userHasContacts: Boolean = false
    ): List<ShortcutOption> {
        val shortcuts = getAllShortcuts().filter { shortcut ->
            when (shortcut) {
                ShortcutOption.SECURE_NOW -> !isPassphraseVerified
                ShortcutOption.SCAN_QR -> userHasBalance
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
} 