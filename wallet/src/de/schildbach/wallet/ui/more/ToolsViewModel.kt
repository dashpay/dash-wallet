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

package de.schildbach.wallet.ui.more

import android.content.ClipData
import android.content.ClipboardManager
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.data.BlockchainStateDao
import de.schildbach.wallet.transactions.TaxBitExporter
import de.schildbach.wallet.transactions.TransactionExporter
import kotlinx.coroutines.launch
import org.bitcoinj.crypto.DeterministicKey
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.TransactionMetadataProvider
import java.util.*
import javax.inject.Inject

@HiltViewModel
class ToolsViewModel @Inject constructor(
    private val walletData: WalletDataProvider,
    private val clipboardManager: ClipboardManager,
    private val transactionMetadataProvider: TransactionMetadataProvider,
    val blockchainStateDao: BlockchainStateDao,
) : ViewModel() {

    val blockchainState = blockchainStateDao.load()

    val xpub: String
    val xpubWithCreationDate: String

    init {
        val extendedKey: DeterministicKey = walletData.wallet!!.watchingKey
        xpub = extendedKey.serializePubB58(Constants.NETWORK_PARAMETERS)
        xpubWithCreationDate = String.format(
            Locale.US,
            "%s?c=%d&h=bip44",
            xpub,
            extendedKey.creationTimeSeconds,
        )
    }

    fun copyXpubToClipboard() {
        clipboardManager.setPrimaryClip(
            ClipData.newPlainText(
                "Dash Wallet extended public key",
                xpub,
            ),
        )
    }

    val transactionExporter = MutableLiveData<TransactionExporter>()
    fun getTransactionExporter() {
        viewModelScope.launch {
            val list = transactionMetadataProvider.getAllTransactionMetadata()

            val map = if (list.isNotEmpty()) {
                list.associateBy({ it.txId }, { it })
            } else {
                mapOf()
            }
            transactionExporter.value = TaxBitExporter(
                walletData.wallet!!,
                map,
            )
        }
    }
}
