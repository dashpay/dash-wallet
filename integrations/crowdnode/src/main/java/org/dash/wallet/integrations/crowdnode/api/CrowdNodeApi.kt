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
import android.content.Intent
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import org.bitcoinj.core.*
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.NotificationService
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.transactions.LockedTransaction
import org.dash.wallet.integrations.crowdnode.R
import org.dash.wallet.integrations.crowdnode.transactions.*
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
    val existingAccountAddress: Address?

    suspend fun signUp(accountAddress: Address)
    fun showNotificationOnFinished(show: Boolean, clickIntent: Intent? = null)
}

class CrowdNodeBlockchainApi @Inject constructor(
    private val paymentService: SendPaymentService,
    private val walletDataProvider: WalletDataProvider,
    private val notificationService: NotificationService,
    @ApplicationContext private val appContext: Context
): CrowdNodeApi {
    private var showNotificationOnFinished = false
    private var notificationIntent: Intent? = null

    override val signUpStatus = MutableStateFlow(SignUpStatus.NotStarted)
    override var existingAccountAddress: Address? = null
        private set

    init {
        checkCrowdNodeTransactions()
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

        if (showNotificationOnFinished) {
            notificationService.showNotification(
                "crowdnode_ready",
                appContext.getString(R.string.crowdnode_account_ready),
                notificationIntent
            )
        }
    }

    override fun showNotificationOnFinished(show: Boolean, clickIntent: Intent?) {
        this.showNotificationOnFinished = show
        notificationIntent = if (show) clickIntent else null
    }

    private fun checkCrowdNodeTransactions() {
        if (signUpStatus.value == SignUpStatus.NotStarted) {
            val wrappedTransactions = walletDataProvider.wrapAllTransactions(CrowdNodeFullTxSet())
            val crowdNodeFullSet = wrappedTransactions.firstOrNull { it is CrowdNodeFullTxSet }
            (crowdNodeFullSet as? CrowdNodeFullTxSet)?.let { set ->
                existingAccountAddress = set.accountAddress

                if (set.hasWelcomeToApiResponse) {
                    signUpStatus.value = SignUpStatus.Finished
                    return
                }

                if (set.hasAcceptTermsResponse) {
                    signUpStatus.value = SignUpStatus.AcceptingTerms
                }
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
        val requestValue = CrowdNodeSignUpTx.SIGNUP_REQUEST_CODE + Coin.SATOSHI
        val signUpTx = paymentService.sendCoins(CrowdNodeConstants.CROWDNODE_ADDRESS, requestValue, accountAddress)
        Log.i("CROWDNODE", "signUp conf: ${signUpTx.confidence}; transo: ${signUpTx}")
        val tx = walletDataProvider.observeTransactions(
            CrowdNodeAcceptTermsResponse(),
            CrowdNodeErrorResponse(requestValue)
        ).first()

        if (CrowdNodeErrorResponse(requestValue).matches(tx)) {
            Log.i("CROWDNODE", "CrowdNodeErrorResponse CrowdNodeErrorResponse CrowdNodeErrorResponse")
        }

        return tx
    }

    private suspend fun acceptTerms(accountAddress: Address): Transaction {
        val requestValue = CrowdNodeAcceptTermsTx.ACCEPT_TERMS_REQUEST_CODE + Coin.SATOSHI
        val acceptTx = paymentService.sendCoins(CrowdNodeConstants.CROWDNODE_ADDRESS, requestValue, accountAddress)
        Log.i("CROWDNODE", "acceptTx conf: ${acceptTx.confidence}; transo: ${acceptTx}")
        val tx = walletDataProvider.observeTransactions(
            CrowdNodeWelcomeToApiResponse(),
            CrowdNodeErrorResponse(requestValue)
        ).first()

        if (CrowdNodeErrorResponse(requestValue).matches(tx)) {
            Log.i("CROWDNODE", "CrowdNodeErrorResponse CrowdNodeErrorResponse CrowdNodeErrorResponse")
        }

        return tx
    }
}