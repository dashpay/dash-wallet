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
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import org.bitcoinj.core.Address
import org.bitcoinj.script.ScriptPattern
import org.bitcoinj.wallet.KeyChain
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dash.wallet.common.util.Constants.DASH_CURRENCY
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

    var signUpUrl: String? = null

    suspend fun sendTransactionInformation(): Boolean {
        val wallet = walletDataProvider.wallet!!
        val transactions = wallet.getTransactions(false)
        val addresses = if (transactions.isEmpty()) {
            listOf(
                ZenLedgerAddress(
                    DASH_CURRENCY,
                    DASH_CURRENCY,
                    wallet.currentAddress(KeyChain.KeyPurpose.RECEIVE_FUNDS).toBase58(),
                    "Dash Wallet"
                )
            )
        } else {
            val addresses = arrayListOf<ZenLedgerAddress>()
            transactions.forEach { tx ->
                tx.outputs.forEach { output ->
                    if (output.isMine(wallet)) {
                        log.info(
                            output.value.toFriendlyString(),
                            Address.fromPubKeyHash(
                                Constants.NETWORK_PARAMETERS,
                                ScriptPattern.extractHashFromP2PKH(output.scriptPubKey)
                            ).toBase58()
                        )
                        addresses.add(
                            ZenLedgerAddress(
                                DASH_CURRENCY,
                                DASH_CURRENCY,
                                Address.fromPubKeyHash(
                                    Constants.NETWORK_PARAMETERS,
                                    ScriptPattern.extractHashFromP2PKH(output.scriptPubKey)
                                ).toBase58(),
                                "Dash Wallet"
                            )
                        )
                    }
                }
            }
            addresses
        }

        return try {
            if (zenLedgerClient.hasValidCredentials) {
                zenLedgerClient.getToken()
                log.info("zenledger: obtained token successfully")
                val request = ZenLedgerCreatePortfolioRequest(addresses)
                signUpUrl = zenLedgerClient.getSignupUrl(request)
                log.info("zenledger: obtained signup url successfully: {}", signUpUrl)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            log.error("zenledger: send addresses error:", e)
            false
        }
    }
}
