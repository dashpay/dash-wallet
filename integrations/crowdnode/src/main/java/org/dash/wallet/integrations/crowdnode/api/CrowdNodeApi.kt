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

package org.dash.wallet.integrations.crowdnode.api

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import org.bitcoinj.core.*
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.NotificationService
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.transactions.LockedTransaction
import org.dash.wallet.integrations.crowdnode.R
import org.dash.wallet.integrations.crowdnode.transactions.CrowdNodeAcceptTermsResponse
import org.dash.wallet.integrations.crowdnode.transactions.CrowdNodeWelcomeToApiResponse
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConstants
import javax.inject.Inject

enum class SignUpStatus {
    NotStarted,
    FundingWallet,
    SigningUp,
    AcceptingTerms,
    Finished
}

interface CrowdNodeApi {
    val signUpStatus: StateFlow<SignUpStatus>

    fun findCrowdNodeAccount(): Address?
    suspend fun signUp(accountAddress: Address)
}

class CrowdNodeBlockchainApi @Inject constructor(
    private val paymentService: SendPaymentService,
    private val walletDataProvider: WalletDataProvider,
    private val notificationService: NotificationService,
    @ApplicationContext private val appContext: Context
): CrowdNodeApi {
    companion object {
        private val SIGNUP_REQUEST: Coin = Coin.valueOf(131072)
        private val ACCEPT_TERMS: Coin = Coin.valueOf(65536)
    }

    override val signUpStatus = MutableStateFlow(SignUpStatus.NotStarted)

    init {
        checkCrowdNodeTransactions()
    }

    override fun findCrowdNodeAccount(): Address? {
        val transactions = walletDataProvider.getTransactions()
        val crowdNodeWelcomeResponse = CrowdNodeWelcomeToApiResponse()
        transactions.forEach { tx ->
            if (crowdNodeWelcomeResponse.matches(tx)) {
                return crowdNodeWelcomeResponse.toAddress
            }
        }

        return null
    }

    override suspend fun signUp(accountAddress: Address) {
        Log.i("CROWDNODE", "sending to address: ${CrowdNodeConstants.CROWDNODE_ADDRESS.toBase58()}")
        Log.i("CROWDNODE", "sending from address: ${accountAddress.toBase58()}")
        signUpStatus.value = SignUpStatus.FundingWallet
        val topUpFirst = topUpAddress(accountAddress)
        Log.i("CROWDNODE", "topUpFirst conf: ${topUpFirst.confidence}; transo: ${topUpFirst}")
        signUpStatus.value = SignUpStatus.SigningUp
        val signUpResponseTx = makeSignUpRequest(accountAddress)
        Log.i("CROWDNODE", "signUpResponseTx conf: ${signUpResponseTx.confidence}; transo: ${signUpResponseTx}")
        signUpStatus.value = SignUpStatus.AcceptingTerms
        val acceptTermsResponseTx = acceptTerms(accountAddress)
        Log.i("CROWDNODE", "acceptTermsResponseTx conf: ${acceptTermsResponseTx.confidence}; transo: ${acceptTermsResponseTx}")
        signUpStatus.value = SignUpStatus.Finished
        notificationService.showNotification(
            "crowdnode_ready",
            appContext.getString(R.string.crowdnode),
            appContext.getString(R.string.crowdnode_account_ready)
        )
    }

    private fun checkCrowdNodeTransactions() {
        val crowdNodeWelcomeResponse = CrowdNodeWelcomeToApiResponse()
        val crowdNodeAcceptTermsResponse = CrowdNodeAcceptTermsResponse()

        for (tx in walletDataProvider.getTransactions()) {
            if (crowdNodeWelcomeResponse.matches(tx)) {
                signUpStatus.value = SignUpStatus.Finished
                return
            }

            if (crowdNodeAcceptTermsResponse.matches(tx)) {
                signUpStatus.value = SignUpStatus.AcceptingTerms
            }
        }
    }

    private suspend fun topUpAddress(accountAddress: Address): Transaction {
        val topUpTx = paymentService.sendCoins(accountAddress, CrowdNodeConstants.MINIMUM_REQUIRED_DASH)
        Log.i("CROWDNODE", "init: ${topUpTx.txId}")
        Log.i("CROWDNODE", "init conf: ${topUpTx.confidence}; transo: ${topUpTx}")
        return walletDataProvider.observeTransactions(LockedTransaction(topUpTx.txId)).first()
    }

    private suspend fun makeSignUpRequest(accountAddress: Address): Transaction {
        val requestValue = CrowdNodeConstants.CROWDNODE_OFFSET + SIGNUP_REQUEST
        val signUpTx = paymentService.sendCoins(CrowdNodeConstants.CROWDNODE_ADDRESS, requestValue, accountAddress)
        Log.i("CROWDNODE", "signUp conf: ${signUpTx.confidence}; transo: ${signUpTx}")
        return walletDataProvider.observeTransactions(CrowdNodeAcceptTermsResponse()).first()
    }

    private suspend fun acceptTerms(accountAddress: Address): Transaction {
        val requestValue = CrowdNodeConstants.CROWDNODE_OFFSET + ACCEPT_TERMS
        val acceptTx = paymentService.sendCoins(CrowdNodeConstants.CROWDNODE_ADDRESS, requestValue, accountAddress)
        Log.i("CROWDNODE", "acceptTx conf: ${acceptTx.confidence}; transo: ${acceptTx}")
        return walletDataProvider.observeTransactions(CrowdNodeWelcomeToApiResponse()).first()
    }
}