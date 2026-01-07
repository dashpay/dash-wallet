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
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.database.dao.BlockchainStateDao
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.transactions.TaxBitExporter
import de.schildbach.wallet.transactions.TransactionExporter
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    val dashPayConfig: DashPayConfig,
    val identityConfig: BlockchainIdentityConfig
) : ViewModel() {
    val blockchainState = blockchainStateDao.observeState()

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

    suspend fun getTransactionExporter(): TransactionExporter = withContext(Dispatchers.IO) {
        TaxBitExporter(
            transactionMetadataProvider,
            walletData.wallet!!,
        )
    }

    suspend fun setCreditsExplained() = withContext(Dispatchers.IO) {
        dashPayConfig.set(DashPayConfig.CREDIT_INFO_SHOWN, true)
    }
    suspend fun creditsExplained() = withContext(Dispatchers.IO) {
        dashPayConfig.get(DashPayConfig.CREDIT_INFO_SHOWN) ?: false
    }

    suspend fun hasUsername(): Boolean = withContext(Dispatchers.IO) {
        identityConfig.get(BlockchainIdentityConfig.IDENTITY_ID) != null &&
                BlockchainIdentityData.CreationState.valueOf(identityConfig.get(BlockchainIdentityConfig.CREATION_STATE)
                    ?: "NONE") >= BlockchainIdentityData.CreationState.DONE
    }
}
