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
import org.bitcoinj.script.ScriptPattern
import org.bitcoinj.wallet.SendRequest
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.util.toCoin
import org.dash.wallet.integrations.maya.model.IncorrectSwapOutputCount
import org.dash.wallet.integrations.maya.model.SwapQuoteRequest
import org.dash.wallet.integrations.maya.model.SwapTradeUIModel
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
        val params = walletProviderData.networkParameters
        val resultSwapTrade = mayaWebApi.getSwapInfo(
            SwapQuoteRequest(
                amount = swapTradeUIModel.amount,
                source_maya_asset = "DASH.DASH",
                target_maya_asset = swapTradeUIModel.outputAsset,
                fiatCurrency = swapTradeUIModel.amount.fiatCode,
                targetAddress = swapTradeUIModel.destinationAddress,
                maximum = swapTradeUIModel.maximum
            )
        )
        if (resultSwapTrade is ResponseResource.Success) {
            try {
                val sendRequest: SendRequest
                val memo = swapTradeUIModel.memo ?: "=:${resultSwapTrade.value.outputAsset}:${resultSwapTrade.value.destinationAddress}"
                val tx = Transaction(params)

                // set outputs according to:
                //   https://docs.mayaprotocol.com/mayachain-dev-docs/concepts/sending-transactions#utxo-chains
                // Send the transaction with Asgard vault as VOUT0
                if (!swapTradeUIModel.maximum) {
                    val dashAmountWithFees = if (!swapTradeUIModel.maximum) {
                        (resultSwapTrade.value.amount.dash + resultSwapTrade.value.feeAmount.dash)
                    } else {
                        resultSwapTrade.value.amount.dash
                    }.setScale(8, RoundingMode.HALF_UP).toCoin()
                    tx.addOutput(
                        dashAmountWithFees,
                        Address.fromBase58(params, resultSwapTrade.value.vaultAddress)
                    )
                    // Include the memo as an OP_RETURN in VOUT1
                    // memo documentation: https://docs.mayaprotocol.com/mayachain-dev-docs/concepts/transaction-memos#swap
                    // SWAP:ASSET:DESTADDR[:AFFILIATE:FEE]
                    log.info("memo: {}", memo)
                    tx.addOutput(
                        TransactionOutput(
                            params,
                            tx,
                            Coin.ZERO,
                            ScriptBuilder.createOpReturnScript(memo.toByteArray()).program
                        )
                    )
                    sendRequest = SendRequest.forTx(tx)
                } else {
                    sendRequest = SendRequest.emptyWallet(Address.fromBase58(params, swapTradeUIModel.vaultAddress))
                }

                // Override randomised VOUT ordering; MAYAChain requires specific output ordering.
                sendRequest.sortByBIP69 = false // we don't want the output order changed
                sendRequest.shuffleOutputs = false // we don't want the output order changed

                // this will complete the transaction by adding inputs and an output for change
                sendPaymentService.completeTransaction(sendRequest)

                // verify that there are only 3 outputs in the transaction
                if (!swapTradeUIModel.maximum && sendRequest.tx.outputs.size != 3) {
                    return ResponseResource.Failure(
                        IncorrectSwapOutputCount(sendRequest.tx),
                        false,
                        0,
                        null
                    )
                }

                if (swapTradeUIModel.maximum) {
                    // Include the memo as an OP_RETURN in VOUT1
                    // memo documentation: https://docs.mayaprotocol.com/mayachain-dev-docs/concepts/transaction-memos#swap
                    // SWAP:ASSET:DESTADDR[:AFFILIATE:FEE]
                    sendRequest.tx.addOutput(
                        TransactionOutput(
                            params,
                            tx,
                            Coin.ZERO,
                            ScriptBuilder.createOpReturnScript(memo.toByteArray()).program
                        )
                    )
                    // account for the size and possibly larger signatures when re-signed
                    val size = sendRequest.tx.bitcoinSerialize().size + sendRequest.tx.inputs.size
                    sendRequest.tx.outputs[0].value = swapTradeUIModel.amount.dash.toCoin() -
                            Coin.valueOf(size * Transaction.REFERENCE_DEFAULT_MIN_TX_FEE.value / 1000)

                } else {
                    // Pass all change back to the VIN0 address in VOUT2
                    val connectedOutput = sendRequest.tx.getInput(0).connectedOutput
                        ?: return ResponseResource.Failure(
                            MayaException("transaction input not connected"),
                            false,
                            0,
                            null
                        )
                    val scriptPubKey = connectedOutput.scriptPubKey

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
                }

                // remove all signatures since we changed the last output.
                for (input in sendRequest.tx.inputs) {
                    input.clearScriptBytes()
                }

                log.info("maya swap transaction: {}", sendRequest.tx)

                sendPaymentService.signTransaction(sendRequest)
                log.info("maya swap transaction resigned: {}", sendRequest.tx)

                // check that vout3 is using vin0
                if (!swapTradeUIModel.maximum && ScriptPattern.isP2PKH(sendRequest.tx.outputs[2].scriptPubKey)) {
                    val input0 = sendRequest.tx.inputs[0]
                    if (sendRequest.tx.outputs[2].scriptPubKey != input0.connectedOutput?.scriptPubKey) {
                        return ResponseResource.Failure(MayaException("vout3 script != vin0"), false, 0, null)
                    }
                }
                // check the fee
                val fee = sendRequest.tx.fee / sendRequest.tx.bitcoinSerialize().size * 1000
                if (fee < Transaction.DEFAULT_TX_FEE) {
                    return ResponseResource.Failure(MayaException("swap transaction fee too small"), false, 0, null)
                }

                // send the transaction
                log.info("maya swap transaction: {}", sendRequest.tx.toStringHex())
                val sentTransaction = sendPaymentService.sendTransaction(sendRequest)
                swapTradeUIModel.txid = sentTransaction.txId
                return ResponseResource.Success(swapTradeUIModel)
            } catch (e: InsufficientMoneyException) {
                return ResponseResource.Failure(e, false, 0, e.message)
            }
        } else {
            return resultSwapTrade
        }
    }
}
