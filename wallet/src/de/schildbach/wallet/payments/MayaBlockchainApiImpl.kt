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
import de.schildbach.wallet.service.platform.sdk.BridgedTxResult
import de.schildbach.wallet.service.platform.sdk.ReservationLockMirror
import de.schildbach.wallet.service.platform.sdk.SdkBridgedTransactionFactory
import de.schildbach.wallet.service.platform.sdk.SdkL1SendService
import de.schildbach.wallet.service.platform.sdk.SdkWriteResult
import de.schildbach.wallet.service.platform.sdk.toSdkNetwork
import kotlinx.coroutines.CancellationException
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.services.InsufficientFundsException
import org.dash.wallet.integrations.maya.api.MayaBlockchainApi
import org.dash.wallet.integrations.maya.api.MayaException
import org.dash.wallet.integrations.maya.api.MayaWebApi
import org.dash.wallet.integrations.maya.model.SwapQuoteRequest
import org.dash.wallet.integrations.maya.model.SwapTradeUIModel
import org.dashfoundation.dashsdk.keywallet.DecodedTransaction
import org.dashfoundation.dashsdk.keywallet.TransactionDecoder
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.math.RoundingMode
import javax.inject.Inject

/**
 * The exact scriptPubKey a MAYACHAIN memo output must carry: `OP_RETURN`
 * (0x6a) followed by the minimal push of [memo] — a direct-length push up
 * to 75 bytes, `OP_PUSHDATA1` (0x4c) beyond (the 80-byte relay ceiling
 * keeps anything larger out). Pure, so the verifier can compare the SDK's
 * output byte-for-byte instead of pattern-matching.
 */
internal fun expectedOpReturnScript(memo: ByteArray): ByteArray {
    require(memo.isNotEmpty()) { "memo must not be empty" }
    require(memo.size <= SdkL1SendService.MAX_MAYA_MEMO_BYTES) { "memo exceeds the OP_RETURN limit" }
    return if (memo.size <= 75) {
        byteArrayOf(0x6a, memo.size.toByte()) + memo
    } else {
        byteArrayOf(0x6a, 0x4c, memo.size.toByte()) + memo
    }
}

/**
 * Pre-broadcast verification of the MAYACHAIN UTXO deposit shape
 * (https://docs.mayaprotocol.com/mayachain-dev-docs/concepts/sending-transactions,
 * "UTXO Chains") against the SDK-decoded signed transaction:
 *
 * - `VOUT0` pays [vaultAddressBase58] exactly [vaultDuffs];
 * - `VOUT1` is a zero-value output whose script is byte-for-byte the
 *   OP_RETURN push of [memo] ([expectedOpReturnScript]);
 * - at most one further output, and when present it is P2PKH change paying
 *   the FIRST input's own address (MAYAChain identifies the depositor by
 *   VIN0 and sends refunds there — change anywhere else strands a refund).
 *
 * Returns null when the shape holds, otherwise a human-readable reason.
 * Nothing has been broadcast when this runs, so a non-null result is always
 * recoverable: release the reservation and surface the error. Pure over the
 * decoded transaction — host-testable without a wallet or native library.
 */
internal fun verifyMayaDepositShape(
    tx: DecodedTransaction,
    vaultAddressBase58: String,
    vaultDuffs: Long,
    memo: ByteArray
): String? {
    if (tx.inputs.isEmpty()) {
        return "transaction has no inputs"
    }
    if (tx.outputs.size !in 2..3) {
        return "expected 2 or 3 outputs (vault, memo[, change]), found ${tx.outputs.size}"
    }

    val vaultOutput = tx.outputs[0]
    if (vaultOutput.address == null) {
        return "VOUT0 is not a plain address output"
    }
    if (vaultOutput.address != vaultAddressBase58) {
        return "VOUT0 pays ${vaultOutput.address}, expected the Asgard vault $vaultAddressBase58"
    }
    if (vaultOutput.valueDuffs != vaultDuffs) {
        return "VOUT0 carries ${vaultOutput.valueDuffs} duffs, expected $vaultDuffs"
    }

    val memoOutput = tx.outputs[1]
    if (memoOutput.valueDuffs != 0L) {
        return "VOUT1 OP_RETURN must be zero-value, carries ${memoOutput.valueDuffs} duffs"
    }
    if (!memoOutput.scriptPubkey.contentEquals(expectedOpReturnScript(memo))) {
        return "VOUT1 is not the OP_RETURN of the swap memo"
    }

    if (tx.outputs.size == 3) {
        val change = tx.outputs[2]
        // P2PKH shape: OP_DUP OP_HASH160 <20-byte hash> OP_EQUALVERIFY OP_CHECKSIG.
        val script = change.scriptPubkey
        val isP2pkh = script.size == 25 &&
            script[0] == 0x76.toByte() && script[1] == 0xa9.toByte() && script[2] == 0x14.toByte() &&
            script[23] == 0x88.toByte() && script[24] == 0xac.toByte()
        if (!isP2pkh || change.address == null) {
            return "VOUT2 change is not P2PKH"
        }
        // change-to-VIN0: the decoder recovers VIN0's address from a
        // P2PKH-shaped scriptSig (`<sig> <pubkey>`). Only checkable when
        // that recovery succeeded; otherwise the engine's own
        // change_to_first_input contract is the guarantee.
        val vin0Address = tx.inputs[0].address
        if (vin0Address != null && change.address != vin0Address) {
            return "VOUT2 change does not pay VIN0's address"
        }
    }
    return null
}

/**
 * The vault amount [verifyMayaDepositShape] must find at VOUT0.
 *
 * For an ordinary sell the app chose the amount, so the quote IS the
 * expectation and any deviation is a defect.
 *
 * A MAX sell is a DRAIN: the ENGINE sets the vault output to
 * (total inputs − fee), so the app never supplied that number and the quote
 * is only a FLOOR — which is exactly how the two guards around the build
 * treat it (both abort on `<`, never on `>`). Holding the shape check to
 * the quote instead would reject a drain that legitimately delivers MORE
 * than quoted, which is what the balance moving between quote and build
 * normally produces.
 *
 * So a max sell is verified against [drainDeliverableDuffs] — the value Rust
 * computed from the REGISTERED transaction. That keeps the check exact
 * rather than loosening it to a range, and makes it a genuine cross-check:
 * the decoded host bytes must agree with what the engine registered, and a
 * disagreement between those two is precisely the failure the check exists
 * to catch.
 */
internal fun expectedVaultDuffs(
    isMaxSell: Boolean,
    quotedDuffs: Long,
    drainDeliverableDuffs: Long
): Long = if (isMaxSell) drainDeliverableDuffs else quotedDuffs

/**
 * Wallet-module implementation of the Maya integration's [MayaBlockchainApi]:
 * builds the swap deposit on the Kotlin SDK's deferred build/broadcast
 * surface ([SdkL1SendService.buildDeferredMayaDeposit] — vault VOUT0,
 * OP_RETURN memo VOUT1, change back to VIN0's address VOUT2, no BIP-69
 * reordering), verifies the shape from the signed bytes BEFORE anything
 * reaches the network ([verifyMayaDepositShape], over the SDK's own
 * [TransactionDecoder]), then broadcasts. Lives here so integrations/maya
 * stays free of wallet-engine types.
 *
 * DASHJ-FREE: building, signing, decoding, verifying and broadcasting all
 * run on the SDK. The only dashj left on this flow is inside the
 * transition-only [ReservationLockMirror] (which dies with Phase 2) and
 * the shared display bridge.
 *
 * Failure semantics (funds-critical):
 * - build/verify failure → reservation released, recoverable error, no
 *   funds moved;
 * - broadcast refused provably pre-network → released, recoverable error;
 * - broadcast outcome AMBIGUOUS → the reservation is KEPT (releasing would
 *   let a rebuilt retry select different inputs and pay the vault twice if
 *   the first deposit did reach the network — the BIP70 field-test lesson)
 *   and the error tells the user not to retry.
 *
 * MAX sells never under-deliver: the quote is set to [maxSwapDepositAmount],
 * which is a drain measured through the engine rather than balance arithmetic,
 * and the deposit that follows runs the same drain. Before broadcasting, the
 * amount the SIGNED transaction actually delivers is checked against the quote
 * — not a re-measurement, so nothing that moved in between can defeat it — and
 * a shortfall aborts rather than quietly paying the vault less than quoted.
 */
class MayaBlockchainApiImpl @Inject constructor(
    private val sdkL1SendService: SdkL1SendService,
    private val mayaWebApi: MayaWebApi,
    private val reservationLockMirror: ReservationLockMirror,
    private val bridgedTransactionFactory: SdkBridgedTransactionFactory
) : MayaBlockchainApi {
    companion object {
        private val log: Logger = LoggerFactory.getLogger(MayaBlockchainApiImpl::class.java)

        /** Duffs per DASH as a decimal shift (1 DASH = 1e8 duffs). */
        private const val DUFFS_DECIMAL_SHIFT = 8
    }

    override suspend fun maxSwapDepositAmount(): Dash =
        Dash(sdkL1SendService.maxMayaDepositDuffs())

    override suspend fun commitSwapTransaction(
        tradeId: String,
        swapTradeUIModel: SwapTradeUIModel
    ): ResponseResource<SwapTradeUIModel> {
        log.info("commitSwapTransaction($tradeId, $swapTradeUIModel")
        // A MAX sell arrives quoted at the FULL spendable balance (the UI fills
        // the amount from the balance so `maximum` can be detected by equality).
        // The mining fee has to come from somewhere, so re-quote at the measured
        // maximum deposit before asking Maya for a price — quoting the full
        // balance would price a deposit that cannot be built, and paying the
        // vault less than the quote is exactly the under-delivery we refuse.
        val quoteAmount = if (swapTradeUIModel.maximum) {
            // Contained: the measurement refuses (throws) when the wallet holds
            // app-locked outputs a max deposit would sweep, and can fail on
            // gate/bind errors — surface those as a recoverable failure rather
            // than letting them escape into the caller's scope.
            val maxDeposit = try {
                maxSwapDepositAmount()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                log.error("maya max sell: deposit fee measurement failed", t)
                return ResponseResource.Failure(
                    (t as? Exception) ?: MayaException(t.message ?: "could not size the swap deposit"),
                    false,
                    0,
                    t.message
                )
            }
            if (maxDeposit.duffs <= 0L) {
                return ResponseResource.Failure(
                    MayaException("balance too low to cover a swap deposit and its fee"),
                    false,
                    0,
                    null
                )
            }
            log.info(
                "maya max sell: re-quoting at the measured max deposit {} (was {})",
                maxDeposit.toFriendlyString(),
                swapTradeUIModel.amount.dash
            )
            swapTradeUIModel.amount.copy().apply { dash = maxDeposit.toBigDecimal() }
        } else {
            swapTradeUIModel.amount
        }
        val resultSwapTrade = mayaWebApi.getSwapInfo(
            SwapQuoteRequest(
                amount = quoteAmount,
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
            // BigDecimal DASH → duffs by decimal shift; longValueExact is
            // safe because the scale is pinned to 8 first.
            val quotedDuffs = if (!swapTradeUIModel.maximum) {
                swapTradeUIModel.amount.dash + swapTradeUIModel.feeAmount.dash
            } else {
                swapTradeUIModel.amount.dash
            }.setScale(DUFFS_DECIMAL_SHIFT, RoundingMode.HALF_UP)
                .movePointRight(DUFFS_DECIMAL_SHIFT)
                .longValueExact()

            val vaultDuffs = quotedDuffs

            // A MAX sell was quoted at the drain-measured maximum deposit.
            // Re-measure with the REAL memo before
            // building: if the spendable balance dropped since the quote, the
            // deposit can no longer pay the quoted amount, and paying the vault
            // LESS than quoted is never acceptable — NEAR Intents refuses
            // under-delivery (~1h wait, then a refund minus 0.001 DASH) and Maya
            // would execute a swap for an amount the user never agreed to. Abort
            // with a recoverable error and let the user re-quote instead.
            //
            // This can only fire on a real balance drop: the quote reserved for a
            // worst-case 80-byte memo, so re-measuring with the actual (shorter
            // or equal) memo can only raise the ceiling, never lower it.
            if (swapTradeUIModel.maximum) {
                val maxDeposit = sdkL1SendService.maxMayaDepositDuffs(memoBytes.size)
                if (vaultDuffs > maxDeposit) {
                    log.warn(
                        "maya max sell aborted: quoted {} duffs exceeds the {} duffs now depositable",
                        vaultDuffs, maxDeposit
                    )
                    return ResponseResource.Failure(
                        MayaException(
                            "wallet balance changed; the deposit would fall below the quoted " +
                                "amount — please request a new quote"
                        ),
                        false,
                        0,
                        null
                    )
                }
            }

            // Build + sign with the funding inputs RESERVED, no broadcast.
            // A MAX sell builds as a DRAIN: the engine sets the vault output to
            // (total inputs - fee) with no change, so the deposit delivers the
            // whole account rather than an amount the app derived separately.
            val payment = sdkL1SendService.buildDeferredMayaDeposit(
                swapTradeUIModel.vaultAddress,
                vaultDuffs,
                memoBytes,
                drain = swapTradeUIModel.maximum
            )

            // A drain's amount is the ENGINE's, so check the built transaction
            // against the quote before deciding to broadcast. The pre-build
            // guard above compared a re-measurement; this compares THIS signed
            // transaction — the one that would actually go to the vault — and
            // so cannot be defeated by anything that moved in between. Paying
            // the vault less than quoted is under-delivery: Maya would execute
            // a swap the user never agreed to, and NEAR Intents refuses it
            // outright (~1h wait, then a refund minus 0.001 DASH).
            if (swapTradeUIModel.maximum && payment.deliverableDuffs < vaultDuffs) {
                log.warn(
                    "maya max sell aborted after build: the drain delivers {} duffs, below the quoted {}",
                    payment.deliverableDuffs, vaultDuffs
                )
                sdkL1SendService.releaseDeferredPayment(payment)
                return ResponseResource.Failure(
                    MayaException(
                        "wallet balance changed; the deposit would fall below the quoted " +
                            "amount — please request a new quote"
                    ),
                    false,
                    0,
                    null
                )
            }

            // Assert the deposit shape from the signed bytes BEFORE any
            // broadcast decision — a mis-shaped deposit to a Maya vault
            // strands funds. Decoded with the SDK's own consensus decoder;
            // a decode failure counts as a failed shape check (released,
            // recoverable), never as a broadcastable pass.
            val depositDuffs = expectedVaultDuffs(
                isMaxSell = swapTradeUIModel.maximum,
                quotedDuffs = vaultDuffs,
                drainDeliverableDuffs = payment.deliverableDuffs
            )
            val shapeError = try {
                verifyMayaDepositShape(
                    TransactionDecoder.decode(payment.rawTxBytes, toSdkNetwork(Constants.NETWORK_PARAMETERS)),
                    swapTradeUIModel.vaultAddress,
                    depositDuffs,
                    memoBytes
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                "signed bytes failed to decode: ${t.message}"
            }
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
            // lifetime as the BIP70 mirror: dashj-side spenders (manual
            // sends, the background CoinJoin mixer) have their own coin
            // selection and no view of the SDK reservation. Best-effort; a
            // lock failure must not fail the swap.
            runCatching { reservationLockMirror.setLocks(payment, locked = true) }
                .onFailure { log.warn("failed to mirror the maya reservation into wallet locks", it) }

            log.info("maya swap deposit {}: broadcasting ({} duffs to the vault)", payment.txidHex, depositDuffs)
            return when (val result = sdkL1SendService.broadcastDeferredPayment(payment)) {
                is SdkWriteResult.Broadcast -> {
                    // The mirrored reservation locks are deliberately NOT
                    // cleared here. It looks like a leak — the deposit
                    // succeeded, so why keep holding its inputs? — but
                    // post-cutover the held dashj wallet never learns that
                    // these outpoints were spent. If the display bridge below
                    // returns NotBridged, that stale lock is the only thing
                    // stopping the mixer from selecting a coin that is already
                    // gone. Clearing them would trade a harmless stale lock for
                    // a double-selected input, so leave them.
                    //
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
                    runCatching { reservationLockMirror.setLocks(payment, locked = false) }
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
                // wallet-engine types.
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
}
