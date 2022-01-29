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

package org.dash.wallet.integrations.crowdnode.logic

import android.util.Log
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.TransactionConfidence
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.integrations.crowdnode.utils.Constants
import javax.inject.Inject

enum class SignUpStatus {
    NotStarted,
    WalletFunded,
    SignUpRequested,
    TermsAccepted,
    Finished
}

interface CrowdNodeApi {
    val signUpStatus: StateFlow<SignUpStatus>
    suspend fun signUp(accountAddress: Address)
}

class CrowdNodeBlockchainApi @Inject constructor(
    private val paymentService: SendPaymentService,
    private val walletDataProvider: WalletDataProvider
): CrowdNodeApi {
    companion object {
        private val OFFSET: Coin = Coin.valueOf(20000)
        private val SIGNUP_REQUEST: Coin = Coin.valueOf(131072)
        private val ACCEPT_TERMS: Coin = Coin.valueOf(65536)
    }

    override val signUpStatus = MutableStateFlow(SignUpStatus.NotStarted)
    private val crowdNodeAddress = Address.fromBase58(Constants.NETWORK_PARAMETERS, Constants.CROWD_NODE_ADDRESS)

    // Confidences:
    // Pending/unconfirmed, InstantSendLock: Unknown status
    // Pending/unconfirmed, InstantSendLock: Received, not verified [sig=8eacf56c18e9870568aaebb9ff7b597d88c8b2c4d4c350a63ef8f9536b4fb1533b198530e4ae03887a00d17b1df62f5d07c7d21a3d78d9d9771c8edd0d3050b46823db1ad80151c3f62631d1c59ff3aed32309b166ba1a4ce6030a37626c367a]
    // Pending/unconfirmed, InstantSendLock: Verified [sig=8eacf56c18e9870568aaebb9ff7b597d88c8b2c4d4c350a63ef8f9536b4fb1533b198530e4ae03887a00d17b1df62f5d07c7d21a3d78d9d9771c8edd0d3050b46823db1ad80151c3f62631d1c59ff3aed32309b166ba1a4ce6030a37626c367a]
    // Appeared in best chain at height 660834, depth 1, InstantSendLock: Verified [sig=8eacf56c18e9870568aaebb9ff7b597d88c8b2c4d4c350a63ef8f9536b4fb1533b198530e4ae03887a00d17b1df62f5d07c7d21a3d78d9d9771c8edd0d3050b46823db1ad80151c3f62631d1c59ff3aed32309b166ba1a4ce6030a37626c367a]


    // init conf: Pending/unconfirmed. InstantSendLock: Unknown status
    //

    override suspend fun signUp(accountAddress: Address) {
        Log.i("CROWDNODE", "sending to address: ${crowdNodeAddress.toBase58()}")
        Log.i("CROWDNODE", "sending from address: ${accountAddress.toBase58()}")
        val topUpTx = paymentService.sendCoins(accountAddress, Constants.MINIMUM_REQUIRED_DASH)
        Log.i("CROWDNODE", "init: ${topUpTx.txId}")
        Log.i("CROWDNODE", "init hash: ${topUpTx.hash}")
        Log.i("CROWDNODE", "init conf: ${topUpTx.confidence}; transo: ${topUpTx}")
//            paymentsService.sendCoins(crowdNodeAddress, OFFSET + SIGNUP_REQUEST, accountAddress)
//            paymentsService.sendCoins(crowdNodeAddress, OFFSET + ACCEPT_TERMS, accountAddress)
        val topUpTxHash = topUpTx.txId
        var signUpTxHash =  Sha256Hash.ZERO_HASH

        walletDataProvider.observeTransactions(CrowdNodeTransaction(accountAddress))
            .onEach {
                when (it.txId) {
                    topUpTxHash -> {
                        Log.i("CROWDNODE", "Re-check topUpTx, confidence: ${it.confidence}")
                        if (it.confidence.ixType == TransactionConfidence.IXType.IX_LOCKED) {
                            Log.i("CROWDNODE", "Confidence: Locked")
                            signUpTxHash = paymentService.sendCoins(crowdNodeAddress, OFFSET + SIGNUP_REQUEST, accountAddress).txId
                            Log.i("CROWDNODE", "signup conf: ${topUpTx.confidence}; transo: ${topUpTx}")
                        }
                    }
                    signUpTxHash -> {
                        Log.i("CROWDNODE", "Re-check signUp tx, confidence: ${it.confidence}")
                    }
                }//8d1cfa9d2e5176bd7ec6d8a259992e98667f9a60161b049e7a925e013636a939
            }
            .launchIn(CoroutineScope(Dispatchers.Main)) // TODO: Check shut down, need to stop observing when signup is finished
    }
}