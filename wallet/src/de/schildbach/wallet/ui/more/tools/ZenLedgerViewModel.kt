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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    sealed class ExportResult {
        object Idle : ExportResult()
        object Loading : ExportResult()
        data class Success(val signUpUrl: String) : ExportResult()
        object Error : ExportResult()
    }

    companion object {
        private val log = LoggerFactory.getLogger(ZenLedgerViewModel::class.java)
    }

    private val _exportResult = MutableStateFlow<ExportResult>(ExportResult.Idle)
    val exportResult: StateFlow<ExportResult> = _exportResult.asStateFlow()

    suspend fun isSynced(): Boolean {
        return blockchainStateProvider.getState()?.isSynced() ?: false
    }

    fun export() {
        if (_exportResult.value is ExportResult.Loading) return
        viewModelScope.launch {
            _exportResult.value = ExportResult.Loading
            val url = sendTransactionInformation()
            _exportResult.value = if (url != null) ExportResult.Success(url) else ExportResult.Error
        }
    }

    fun resetExportResult() {
        _exportResult.value = ExportResult.Idle
    }

    private suspend fun sendTransactionInformation(): String? = withContext(Dispatchers.IO) {
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
            val seen = linkedSetOf<String>()
            transactions.forEach { tx ->
                tx.outputs.forEach { output ->
                    if (output.isMine(wallet) && ScriptPattern.isP2PKH(output.scriptPubKey)) {
                        val address = Address.fromPubKeyHash(
                            Constants.NETWORK_PARAMETERS,
                            ScriptPattern.extractHashFromP2PKH(output.scriptPubKey)
                        ).toBase58()
                        log.info("zenledger: output value={}, address={}", output.value.toFriendlyString(), address)
                        seen.add(address)
                    }
                }
            }
            seen.map { ZenLedgerAddress(DASH_CURRENCY, DASH_CURRENCY, it, "Dash Wallet") }
        }

        try {
            if (zenLedgerClient.hasValidCredentials) {
                zenLedgerClient.getToken()
                log.info("zenledger: obtained token successfully")
                val request = ZenLedgerCreatePortfolioRequest(addresses)
                val signUpUrl = zenLedgerClient.getSignupUrl(request)
                log.info("zenledger: obtained signup url successfully: {}", signUpUrl)
                signUpUrl
            } else {
                null
            }
        } catch (e: Exception) {
            log.error("zenledger: send addresses error:", e)
            null
        }
    }
}