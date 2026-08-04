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

package de.schildbach.wallet.payments

import de.schildbach.wallet.Constants
import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.service.platform.sdk.BridgedTxResult
import de.schildbach.wallet.service.platform.sdk.SdkBridgedTransactionFactory
import de.schildbach.wallet.service.platform.sdk.SdkDeferredPayment
import de.schildbach.wallet.service.platform.sdk.SdkL1SendService
import de.schildbach.wallet.service.platform.sdk.SdkWriteResult
import de.schildbach.wallet.util.toDashjCoin
import kotlinx.coroutines.CancellationException
import org.bitcoinj.core.Context
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.Utils
import org.bitcoinj.script.ScriptPattern
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.services.InsufficientFundsException
import org.dash.wallet.common.util.toCoin
import org.dash.wallet.integrations.maya.api.MayaBlockchainApi
import org.dash.wallet.integrations.maya.api.MayaException
import org.dash.wallet.integrations.maya.api.MayaWebApi
import org.dash.wallet.integrations.maya.model.SwapQuoteRequest
import org.dash.wallet.integrations.maya.model.SwapTradeUIModel
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.math.RoundingMode
import javax.inject.Inject

/**
 * Pre-broadcast verification of the MAYACHAIN UTXO deposit shape
 * (https://docs.mayaprotocol.com/mayachain-dev-docs/concepts/sending-transactions,
 * "UTXO Chains") against the SDK-built signed bytes:
 *
 * - `VOUT0` pays [vaultAddressBase58] exactly [vaultDuffs];
 * - `VOUT1` is a zero-value OP_RETURN carrying exactly [memo];
 * - at most one further output, and when present it is P2PKH change paying
 *   the FIRST input's own address (MAYAChain identifies the depositor by
 *   VIN0 and sends refunds there — change anywhere else strands a refund).
 *
 * Returns null when the shape holds, otherwise a human-readable reason.
 * Nothing has been broadcast when this runs, so a non-null result is always
 * recoverable: release the reservation and surface the error. Pure over its
 * inputs — host-testable without a wallet.
 */
internal fun verifyMayaDepositShape(
    rawTxBytes: ByteArray,
    params: NetworkParameters,
    vaultAddressBase58: String,
    vaultDuffs: Long,
    memo: ByteArray
): String? {
    val tx = try {
        Transaction(params, rawTxBytes)
    } catch (e: Exception) {
        return "unparseable transaction: ${e.message}"
    }
    if (tx.inputs.isEmpty()) {
        return "transaction has no inputs"
    }
    if (tx.outputs.size !in 2..3) {
        return "expected 2 or 3 outputs (vault, memo[, change]), found ${tx.outputs.size}"
    }

    val vaultOutput = tx.outputs[0]
    val vaultPaysTo = try {
        vaultOutput.scriptPubKey.getToAddress(params).toBase58()
    } catch (e: Exception) {
        return "VOUT0 is not a plain address output"
    }
    if (vaultPaysTo != vaultAddressBase58) {
        return "VOUT0 pays $vaultPaysTo, expected the Asgard vault $vaultAddressBase58"
    }
    if (vaultOutput.value.value != vaultDuffs) {
        return "VOUT0 carries ${vaultOutput.value.value} duffs, expected $vaultDuffs"
    }

    val memoOutput = tx.outputs[1]
    if (!ScriptPattern.isOpReturn(memoOutput.scriptPubKey)) {
        return "VOUT1 is not an OP_RETURN"
    }
    if (memoOutput.value.value != 0L) {
        return "VOUT1 OP_RETURN must be zero-value, carries ${memoOutput.value.value} duffs"
    }
    val payload = memoOutput.scriptPubKey.chunks.getOrNull(1)?.data
    if (payload == null || !payload.contentEquals(memo)) {
        return "VOUT1 memo does not match the swap memo"
    }

    if (tx.outputs.size == 3) {
        val change = tx.outputs[2]
        if (!ScriptPattern.isP2PKH(change.scriptPubKey)) {
            return "VOUT2 change is not P2PKH"
        }
        // change-to-VIN0: a signed P2PKH input's scriptSig is <sig> <pubkey>,
        // so VIN0's address hash is HASH160 of its second chunk. Only
        // checkable when the input really is P2PKH-signed; a missing pubkey
        // chunk is left to the engine's own change_to_first_input contract.
        val vin0PubKey = tx.getInput(0).scriptSig.chunks.getOrNull(1)?.data
        if (vin0PubKey != null) {
            val changeHash = ScriptPattern.extractHashFromP2PKH(change.scriptPubKey)
            if (!Utils.sha256hash160(vin0PubKey).contentEquals(changeHash)) {
                return "VOUT2 change does not pay VIN0's address"
            }
        }
    }
    return null
}

/**
 * Wallet-module implementation of the Maya integration's [MayaBlockchainApi]:
 * builds the swap deposit on the Kotlin SDK's deferred build/broadcast
 * surface ([SdkL1SendService.buildDeferredMayaDeposit] — vault VOUT0,
 * OP_RETURN memo VOUT1, change back to VIN0's address VOUT2, no BIP-69
 * reordering), verifies the shape from the signed bytes BEFORE anything
 * reaches the network ([verifyMayaDepositShape]), then broadcasts. Lives
 * here so integrations/maya stays free of wallet-engine types.
 *
 * The dashj transaction-construction leg (manual `SendRequest`, output
 * clearing/re-signing, the fresh-Transaction confidence workaround) is
 * DELETED per the replace-then-delete policy — the same treatment BIP70
 * got. The dashj foundation wallet is still used for two bounded jobs:
 * the transition-only reservation mirror (below) and parsing the signed
 * bytes in [verifyMayaDepositShape].
 *
 * Failure semantics (funds-critical):
 * - build/verify failure → reservation released, recoverable error, no
 *   funds moved;
 * - broadcast refused provably pre-network → released, recoverable error;
 * - broadcast outcome AMBIGUOUS → the reservation is KEPT (releasing would
 *   let a rebuilt retry select different inputs and pay the vault twice if
 *   the first deposit did reach the network — the BIP70 field-test lesson)
 *   and the error tells the user not to retry.
 */
class MayaBlockchainApiImpl @Inject constructor(
    private val sdkL1SendService: SdkL1SendService,
    private val mayaWebApi: MayaWebApi,
    private val walletData: WalletData,
    private val bridgedTransactionFactory: SdkBridgedTransactionFactory
) : MayaBlockchainApi {
    companion object {
        private val log: Logger = LoggerFactory.getLogger(MayaBlockchainApiImpl::class.java)

        /**
         * Adjust-down reserve for a MAX sell, in duffs. A max quote is
         * derived from the spendable balance, which leaves nothing for the
         * mining fee — when the engine reports the shortfall (a provably
         * pre-broadcast build failure; nothing was reserved or sent), the
         * build retries ONCE with this reserve carved out of the vault
         * amount. 10 000 duffs covers the fee of a deposit spending ~67
         * inputs at the engine's default rate; any unspent remainder
         * returns as VOUT2 change.
         */
        private const val MAX_SELL_FEE_RESERVE_DUFFS = 10_000L
    }

    override suspend fun commitSwapTransaction(
        tradeId: String,
        swapTradeUIModel: SwapTradeUIModel
    ): ResponseResource<SwapTradeUIModel> {
        log.info("commitSwapTransaction($tradeId, $swapTradeUIModel")
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
        return if (resultSwapTrade is ResponseResource.Success) {
            buildAndSendSwapTx(resultSwapTrade.value)
        } else {
            resultSwapTrade
        }
    }

    override suspend fun buildAndSendSwapTx(
        swapTradeUIModel: SwapTradeUIModel
    ): ResponseResource<SwapTradeUIModel> {
        val params = Constants.NETWORK_PARAMETERS
        try {
            // memo documentation:
            //   https://docs.mayaprotocol.com/mayachain-dev-docs/concepts/transaction-memos#swap
            // SWAP:ASSET:DESTADDR[:AFFILIATE:FEE]
            val memo = swapTradeUIModel.memo
                ?: "=:${swapTradeUIModel.outputAsset}:${swapTradeUIModel.destinationAddress}"
            val memoBytes = memo.toByteArray()
            // Guard the OP_RETURN size up front (the SDK build re-checks
            // pre-reservation): long token identifiers (an asset contract
            // address plus the destination address) are what push a memo
            // past the limit — fail with a real error the UI can surface.
            if (memoBytes.size > SdkL1SendService.MAX_MAYA_MEMO_BYTES) {
                log.error(
                    "maya swap memo too long: {} bytes (max {}): {}",
                    memoBytes.size, SdkL1SendService.MAX_MAYA_MEMO_BYTES, memo
                )
                return ResponseResource.Failure(
                    MayaException(
                        "swap memo too long for OP_RETURN: ${memoBytes.size} > " +
                            "${SdkL1SendService.MAX_MAYA_MEMO_BYTES} bytes"
                    ),
                    false,
                    0,
                    null
                )
            }
            log.info("memo: {}", memo)

            // Vault amount per https://docs.mayaprotocol.com/mayachain-dev-docs/concepts/sending-transactions:
            // the swap fee rides on top of the sell amount for a normal
            // sell; a MAX sell was quoted against the whole spendable
            // balance, so the fee comes out of the quoted amount itself.
            val quotedDuffs = if (!swapTradeUIModel.maximum) {
                swapTradeUIModel.amount.dash + swapTradeUIModel.feeAmount.dash
            } else {
                swapTradeUIModel.amount.dash
            }.setScale(8, RoundingMode.HALF_UP).toCoin().toDashjCoin().value

            // Build + sign with the funding inputs RESERVED, no broadcast.
            // Any throw here is pre-broadcast by construction, so the MAX
            // sell's mining-fee shortfall may be retried once, adjusted
            // down by the reserve (nothing has moved).
            var vaultDuffs = quotedDuffs
            val payment = try {
                sdkL1SendService.buildDeferredMayaDeposit(swapTradeUIModel.vaultAddress, vaultDuffs, memoBytes)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (swapTradeUIModel.maximum && isInsufficientFunds(t) &&
                    quotedDuffs > MAX_SELL_FEE_RESERVE_DUFFS
                ) {
                    vaultDuffs = quotedDuffs - MAX_SELL_FEE_RESERVE_DUFFS
                    log.info(
                        "maya max sell: {} duffs not fundable with the fee; retrying at {} duffs",
                        quotedDuffs, vaultDuffs
                    )
                    sdkL1SendService.buildDeferredMayaDeposit(swapTradeUIModel.vaultAddress, vaultDuffs, memoBytes)
                } else {
                    throw t
                }
            }

            // Assert the deposit shape from the signed bytes BEFORE any
            // broadcast decision — a mis-shaped deposit to a Maya vault
            // strands funds, so this replaces (and strengthens) the old
            // post-completeTx output checks.
            val shapeError = verifyMayaDepositShape(
                payment.rawTxBytes, params, swapTradeUIModel.vaultAddress, vaultDuffs, memoBytes
            )
            if (shapeError != null) {
                log.error("maya swap deposit {} failed shape verification: {}", payment.txidHex, shapeError)
                sdkL1SendService.releaseDeferredPayment(payment)
                return ResponseResource.Failure(
                    MayaException("swap deposit failed pre-broadcast verification: $shapeError"),
                    false,
                    0,
                    null
                )
            }

            // Reservation mirror — TRANSITION-ONLY, same rationale and
            // lifetime as the BIP70 mirror in SendCoinsTaskRunner: dashj-side
            // spenders (manual sends, the background CoinJoin mixer) have
            // their own coin selection and no view of the SDK reservation.
            // Best-effort; a lock failure must not fail the swap.
            runCatching { setReservedOutpointLocks(payment, locked = true) }
                .onFailure { log.warn("failed to mirror the maya reservation into wallet locks", it) }

            log.info("maya swap deposit {}: broadcasting ({} duffs to the vault)", payment.txidHex, vaultDuffs)
            return when (val result = sdkL1SendService.broadcastDeferredPayment(payment)) {
                is SdkWriteResult.Broadcast -> {
                    // Synchronous display bridge (same mechanism as every SDK
                    // send) so the confirmation screen's InstantSend watch and
                    // the tx list see the deposit immediately. Non-fatal: the
                    // funds ARE sent; without the bridge the tx appears on the
                    // next display-sync tick.
                    when (val bridged = bridgedTransactionFactory.bridge(result.value)) {
                        is BridgedTxResult.Bridged -> Unit
                        is BridgedTxResult.NotBridged -> log.warn(
                            "maya swap deposit {} broadcast but the display bridge failed ({})",
                            result.value, bridged.reason
                        )
                    }
                    swapTradeUIModel.txid = result.value
                    ResponseResource.Success(swapTradeUIModel)
                }
                is SdkWriteResult.NotBroadcast -> {
                    // Provably never reached the network: free the inputs so a
                    // retry can rebuild cleanly.
                    runCatching { setReservedOutpointLocks(payment, locked = false) }
                        .onFailure { log.warn("failed to clear the mirrored maya reservation locks", it) }
                    sdkL1SendService.releaseDeferredPayment(payment)
                    ResponseResource.Failure(
                        MayaException("swap deposit was not broadcast (${result.reason}); no funds moved"),
                        false,
                        0,
                        null
                    )
                }
                is SdkWriteResult.Ambiguous -> {
                    // The deposit MAY be on the network. Keep the reservation
                    // AND the mirrored locks: releasing would let a rebuilt
                    // retry select different inputs and pay the vault twice.
                    log.error(
                        "maya swap deposit {} outcome unconfirmed — inputs stay reserved; NOT retryable",
                        payment.txidHex, result.cause
                    )
                    ResponseResource.Failure(
                        MayaException(
                            "swap deposit outcome is unconfirmed — it may already be on the " +
                                "network; check the transaction list before retrying"
                        ),
                        false,
                        0,
                        null
                    )
                }
            }
        } catch (e: CancellationException) {
            // Never convert cancellation into Failure: if the coroutine is
            // cancelled after the broadcast, a Failure would tell the caller
            // the swap failed and invite a retry — a double swap. Propagate
            // so the caller's scope handles it as a cancellation.
            throw e
        } catch (t: Throwable) {
            if (isInsufficientFunds(t)) {
                // Neutral exception so the maya module can detect it without
                // wallet-engine types — the same contract the dashj path kept
                // by converting InsufficientMoneyException.
                return ResponseResource.Failure(InsufficientFundsException(t.message, t), false, 0, t.message)
            }
            log.error("failed to build/send maya swap deposit", t)
            return ResponseResource.Failure(
                (t as? Exception) ?: MayaException(t.message ?: "maya swap deposit failed"),
                false,
                0,
                t.message
            )
        }
    }

    /**
     * The engine's pre-broadcast funding shortfall, however it is phrased
     * across the build layers (key-wallet's `Insufficient funds` Display
     * inside the FFI's build-failure wrapper). Only ever consulted for
     * throws from the BUILD step, which never broadcasts.
     */
    private fun isInsufficientFunds(t: Throwable): Boolean =
        generateSequence(t) { it.cause?.takeIf { cause -> cause !== it } }
            .take(5)
            .any { it.message?.contains("insufficient funds", ignoreCase = true) == true }

    /**
     * Lock/unlock the outpoints [payment]'s signed tx spends in the
     * foundation dashj wallet — the app-side mirror of the SDK's engine
     * reservation, identical to the BIP70 mirror (TRANSITION-ONLY, dies
     * with Phase 2). Pure bookkeeping on the Phase-3 foundation object.
     */
    private fun setReservedOutpointLocks(payment: SdkDeferredPayment, locked: Boolean) {
        val wallet = walletData.wallet ?: return
        Context.propagate(wallet.context)
        val tx = Transaction(Constants.NETWORK_PARAMETERS, payment.rawTxBytes)
        for (input in tx.inputs) {
            val outpoint = input.outpoint
            if (locked) {
                wallet.lockOutput(outpoint)
            } else {
                wallet.unlockOutput(outpoint)
            }
        }
    }
}
