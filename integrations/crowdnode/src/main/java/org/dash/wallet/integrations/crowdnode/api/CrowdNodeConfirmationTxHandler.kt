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

import android.content.Intent
import android.content.res.Resources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.bitcoinj.core.Address
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Transaction
import org.dash.wallet.common.services.NotificationService
import org.dash.wallet.common.transactions.filters.CoinsToAddressTxFilter
import org.dash.wallet.integrations.crowdnode.R
import org.dash.wallet.integrations.crowdnode.model.CrowdNodeException
import org.dash.wallet.integrations.crowdnode.model.OnlineAccountStatus
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConfig
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConstants
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors

class CrowdNodeAPIConfirmationForwarded(
    params: NetworkParameters
): CoinsToAddressTxFilter(
    CrowdNodeConstants.getCrowdNodeAddress(params),
    CrowdNodeConstants.API_CONFIRMATION_DASH_AMOUNT,
    includeFee = true
)

open class CrowdNodeAPIConfirmationTx(
    address: Address
): CoinsToAddressTxFilter(address, CrowdNodeConstants.API_CONFIRMATION_DASH_AMOUNT)

class CrowdNodeAPIConfirmationHandler(
    private val apiAddress: Address,
    private val primaryAddress: Address,
    private val blockchainApi: CrowdNodeBlockchainApi,
    private val notificationService: NotificationService,
    private val crowdNodeConfig: CrowdNodeConfig,
    private val resources: Resources,
    private val intent: Intent?
): CrowdNodeAPIConfirmationTx(apiAddress) {
    companion object {
        private val log = LoggerFactory.getLogger(CrowdNodeAPIConfirmationHandler::class.java)
    }

    private val handlerScope = CoroutineScope(
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    )

    fun handle(tx: Transaction) {
        log.info("Handling confirmation tx: ${tx.txId}")

        handlerScope.launch {
            val statusOrdinal = crowdNodeConfig.get(CrowdNodeConfig.ONLINE_ACCOUNT_STATUS) ?: OnlineAccountStatus.None.ordinal

            if (statusOrdinal == OnlineAccountStatus.Done.ordinal) {
                log.info("API address already confirmed")
                return@launch
            }

            if (fromAddresses.isNotEmpty() && fromAddresses.none { it == primaryAddress }) {
                handleWrongAddressError()
                return@launch
            }

            try {
                blockchainApi.resendConfirmationTx(tx)
            } catch (ex: CrowdNodeException) {
                handleWrongAddressError()
            }
        }
    }

    private suspend fun handleWrongAddressError() {
        log.error("From address detected, but it's not the primary address")
        crowdNodeConfig.set(CrowdNodeConfig.BACKGROUND_ERROR, CrowdNodeException.CONFIRMATION_ERROR)
        notificationService.showNotification(
            "crowdnode_bad_confirmation",
            resources.getString(R.string.crowdnode_bad_confirmation),
            false,
            intent
        )
    }
}