/*
 * Copyright 2023 Dash Core Group.
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

package org.dash.wallet.integrations.maya.api

import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.InsufficientMoneyException
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.script.ScriptBuilder
import org.bitcoinj.wallet.SendRequest
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.util.toCoin
import org.dash.wallet.integrations.maya.model.IncorrectSwapOutputCount
import org.dash.wallet.integrations.maya.model.SwapTradeUIModel
import org.dash.wallet.integrations.maya.model.TradesRequest
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.math.RoundingMode
import javax.inject.Inject

interface MayaBlockchainApi {
    suspend fun commitSwapTransaction(
        tradeId: String,
        swapTradeUIModel: SwapTradeUIModel
    ): ResponseResource<SwapTradeUIModel>
}
class MayaBlockchainApiImpl @Inject constructor(
    private val sendPaymentService: SendPaymentService,
    private val mayaWebApi: MayaWebApi,
    private val walletProviderData: WalletDataProvider
): MayaBlockchainApi {
    companion object {
        private val log: Logger = LoggerFactory.getLogger(MayaBlockchainApiImpl::class.java)
    }

    override suspend fun commitSwapTransaction(
        tradeId: String,
        swapTradeUIModel: SwapTradeUIModel
    ): ResponseResource<SwapTradeUIModel> {
        log.info("commitSwapTransaction($tradeId, $swapTradeUIModel")
        val resultSwapTrade = mayaWebApi.swapTradeInfo(
            TradesRequest(
                amount = swapTradeUIModel.amount,
                source_maya_asset = swapTradeUIModel.inputCurrency,
                target_maya_asset = swapTradeUIModel.outputAsset,
                fiatCurrency = swapTradeUIModel.amount.fiatCode,
                targetAddress = swapTradeUIModel.destinationAddress
            )
        )
        if (resultSwapTrade is ResponseResource.Success) {
            try {
                val tx = Transaction(walletProviderData.networkParameters)
                // set outputs according to:
                //   https://docs.mayaprotocol.com/mayachain-dev-docs/concepts/sending-transactions#utxo-chains
                // Send the transaction with Asgard vault as VOUT0
                val dashAmountWithFees = (resultSwapTrade.value.amount.dash + resultSwapTrade.value.feeAmount.dash)
                    .setScale(8, RoundingMode.HALF_UP)
                    .toCoin()
                tx.addOutput(
                    dashAmountWithFees,
                    Address.fromBase58(walletProviderData.networkParameters, resultSwapTrade.value.vaultAddress)
                )
                // Include the memo as an OP_RETURN in VOUT1
                // memo documentation: https://docs.mayaprotocol.com/mayachain-dev-docs/concepts/transaction-memos#swap
                // SWAP:ASSET:DESTADDR[:AFFILIATE:FEE]
                val memo = "SWAP:${resultSwapTrade.value.outputAsset}:${resultSwapTrade.value.destinationAddress}"
                tx.addOutput(
                    TransactionOutput(
                        walletProviderData.networkParameters,
                        tx,
                        Coin.ZERO,
                        ScriptBuilder.createOpReturnScript(memo.toByteArray()).program
                    )
                )
                val sendRequest = SendRequest.forTx(tx)
                // Override randomised VOUT ordering; MAYAChain requires specific output ordering.
                sendRequest.sortByBIP69 = false // we don't want the order changed
                sendRequest.shuffleOutputs = false // we don't want the order changed
                // this will complete the transaction by adding inputs and an output for change
                sendPaymentService.completeTransaction(sendRequest)

                // verify that there are only 3 outputs in the transaction
                if (sendRequest.tx.outputs.size != 3) {
                    return ResponseResource.Failure(
                        IncorrectSwapOutputCount(sendRequest.tx), false, 0, null
                    )
                }

                // Pass all change back to the VIN0 address in VOUT2
                val inputIndex = sendRequest.tx.getInput(0).index
                val inputTx = sendRequest.tx.getInput(0).connectedTransaction
                val scriptPubKey = inputTx!!.getOutput(inputIndex.toLong()).scriptPubKey

                // to replace output[2], we must clear all outputs and them back
                // this is because Transaction.getOutputs returns an immutable list
                val outputs = sendRequest.tx.outputs.map { it }
                sendRequest.tx.clearOutputs()
                for (i in outputs.indices) {
                    if (i != 2) {
                        sendRequest.tx.addOutput(outputs[i])
                    } else {
                        sendRequest.tx.addOutput(outputs[i].value, scriptPubKey)
                    }
                }

                // remove all signatures since we changed the last output.
                for (input in sendRequest.tx.inputs) {
                    input.clearScriptBytes()
                }

                log.info("maya swap transaction: {}", sendRequest.tx)

                sendPaymentService.signTransaction(sendRequest)
                log.info("maya swap transaction resigned: {}", sendRequest.tx)

                //
                //val sentTransaction = sendPaymentService.sendTransaction(sendRequest)
                //swapTradeUIModel.txid = sentTransaction.txId
                return ResponseResource.Success(swapTradeUIModel)
            } catch (e: InsufficientMoneyException) {
                return ResponseResource.Failure(e, false, 0, e.message)
            }
        } else {
            return resultSwapTrade
        }
    }
}
