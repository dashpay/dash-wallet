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

package de.schildbach.wallet.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import org.bitcoinj.crypto.DeterministicKey
import java.util.*
import javax.inject.Inject

@HiltViewModel
class ToolsViewModel @Inject constructor(
    private val walletApplication: WalletApplication,
    private val clipboardManager: ClipboardManager
) : ViewModel() {

    val blockchainState = AppDatabase.getAppDatabase().blockchainStateDao().load()

    val xpub: String
    val xpubWithCreationDate: String

    init {
        val extendedKey: DeterministicKey = walletApplication.wallet.watchingKey
        xpub = extendedKey.serializePubB58(Constants.NETWORK_PARAMETERS);
        xpubWithCreationDate = String.format(
            Locale.US,
            "%s?c=%d&h=bip44",
            xpub,
            extendedKey.creationTimeSeconds
        )
    }

    fun copyXpubToClipboard() {
        clipboardManager.setPrimaryClip(
            ClipData.newPlainText(
                "Dash Wallet extended public key",
                xpub
            )
        )
    }
}