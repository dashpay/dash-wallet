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

package org.dash.wallet.integrations.crowdnode.transactions

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Transaction
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.transactions.CoinsToAddressTxFilter
import org.dash.wallet.common.transactions.ExactOutputsSelector
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConfig
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConstants
import java.util.concurrent.Executors

class CrowdNodeAPIConfirmationHandler(
    private val config: CrowdNodeConfig,
    private val paymentService: SendPaymentService,
    private val networkParameters: NetworkParameters
) {
    private val handlerScope = CoroutineScope(
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    )

    fun handle(tx: Transaction) {
        Log.i("CROWDNODE", "CrowdNodeConfirmationReceivedHandler handle: $tx")
        handlerScope.launch {
            val selector = ExactOutputsSelector(
                listOf(tx.outputs.first { it.value == CrowdNodeConstants.API_CONFIRMATION_DASH_AMOUNT })
            )
            val resendTx = paymentService.sendCoins(
                CrowdNodeConstants.getCrowdNodeAddress(networkParameters),
                CrowdNodeConstants.API_CONFIRMATION_DASH_AMOUNT,
                selector,
                true
            )
            Log.i("CROWDNODE", "trying to resend: $resendTx")
        }
    }
}