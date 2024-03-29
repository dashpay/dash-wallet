/*
 * Copyright (c) 2024 Dash Core Group
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui.more.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.bitcoinj.core.Address
import org.bitcoinj.core.Transaction
import org.bitcoinj.script.ScriptPattern
import org.bitcoinj.wallet.WalletTransaction
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.BlockchainStateProvider
import org.slf4j.LoggerFactory
import javax.inject.Inject

@HiltViewModel
class ZenLedgerViewModel @Inject constructor(
    private val walletDataProvider: WalletDataProvider,
    private val blockchainStateProvider: BlockchainStateProvider,
    private val zenLedgerClient: ZenLedgerClient
) : ViewModel() {

    companion object {
        private val log = LoggerFactory.getLogger(ZenLedgerViewModel::class.java)
    }
    suspend fun isSynced(): Boolean {
        return blockchainStateProvider.getState()?.isSynced() ?: false
    }

//    private var transactions: Iterable<WalletTransaction> = setOf()
//    private val _hasTransactions = MutableStateFlow(false)
//    val hasTransactions: Flow<Boolean>
//        get() = _hasTransactions
//
//    init {
//        walletDataProvider.observeWalletChanged()
//            .onEach {
//                transactions = walletDataProvider.wallet!!.walletTransactions
//            }
//            .launchIn(viewModelScope)
//    }

    var signUpUrl: String? = null

    // This is a dummy function for now
    suspend fun sendTransactionInformation(): Boolean {
        val wallet = walletDataProvider.wallet!!
        val transactions = wallet.getTransactions(false)
        if (transactions.isEmpty()) {
            return false
        }
        val addresses = arrayListOf<ZenLedgerAddress>()
        transactions.forEach { tx ->
            tx.outputs.forEach { output ->
                if (output.isMine(wallet)) {
                    log.info(
                        "zen output: {} {}",
                        output.value.toFriendlyString(),
                        Address.fromPubKeyHash(
                            Constants.NETWORK_PARAMETERS,
                            ScriptPattern.extractHashFromP2PKH(output.scriptPubKey)
                        ).toString()
                    )
                    addresses.add(
                        ZenLedgerAddress(
                            "DASH",
                            "DASH",
                            Address.fromPubKeyHash(
                                Constants.NETWORK_PARAMETERS,
                                ScriptPattern.extractHashFromP2PKH(output.scriptPubKey)
                            ).toString(),
                            "Dash Wallet"
                        )
                    )
                }
            }
        }
//        return false

        return try {
            if (zenLedgerClient.hasValidCredentials) {
                zenLedgerClient.getToken()

                val request = ZenLedgerCreatePortfolioRequest(addresses)
                signUpUrl = zenLedgerClient.getSignupUrl(request)
                // run custom tab
                true
            } else {
                false
            }
        } catch (e: Exception) {
            log.error("send tx error:", e)
            false
        }
    }
}
