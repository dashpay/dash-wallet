/*
 * Copyright 2026 Dash Core Group.
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

import de.schildbach.wallet.service.platform.sdk.BridgedTxResult
import de.schildbach.wallet.service.platform.sdk.L1SendProbeService
import de.schildbach.wallet.service.platform.sdk.SdkBridgedTransactionFactory
import de.schildbach.wallet.service.platform.sdk.SdkDeferredPayment
import de.schildbach.wallet.service.platform.sdk.SdkL1SendService
import de.schildbach.wallet.service.platform.sdk.SdkWriteResult
import kotlinx.coroutines.CancellationException
import org.bitcoinj.core.Transaction
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What [PendingDirectPaymentVerifier] needs from the Kotlin SDK engine.
 *
 * The verifier was written against dashj: it learns that a payment reached the network from the
 * wallet's confidence table and from the peer group. Once the SDK owns L1 the dashj peer group is
 * held, so neither says anything any more, and BIP70 payments are built by the SDK with their
 * inputs reserved engine-side ([SdkDeferredPayment]). This is the narrow set of engine facts and
 * actions the watch needs on top of what the dashj wallet object still provides.
 */
interface PendingPaymentEngine {
    /**
     * True once the cutover has committed. From then on the dashj peer group is held: its peer
     * count reads 0 and its mempool view is empty, so they are no evidence either way.
     */
    suspend fun sdkOwnsL1(): Boolean

    /**
     * True when the SDK engine holds a record of [txIdHex]. The engine only records a transaction
     * it has observed on the network, and a payment the wallet has not broadcast itself can only
     * be there because the payee broadcast it.
     */
    suspend fun hasObserved(txIdHex: String): Boolean

    /**
     * Commits [rawTx] to the dashj wallet of record the way every SDK send is committed (the
     * bridge: `maybeCommitTx`, the confidence and purpose stamps, the dashj announce, the
     * self-spend grace marker and the send analytics), returning the wallet's live instance, or
     * null when the bridge could not commit it.
     */
    suspend fun bridge(txIdHex: String, rawTx: ByteArray): Transaction?

    /** Consumes [reservation] by broadcasting its signed transaction. Best effort; never throws. */
    suspend fun broadcast(reservation: SdkDeferredPayment)

    /** Gives [reservation]'s inputs back to the engine. Best effort; never throws. */
    suspend fun release(reservation: SdkDeferredPayment)
}

@Singleton
class SdkPendingPaymentEngine @Inject constructor(
    private val sdkL1SendService: SdkL1SendService,
    private val l1SendProbeService: L1SendProbeService,
    private val bridgedTransactionFactory: SdkBridgedTransactionFactory
) : PendingPaymentEngine {
    companion object {
        private val log = LoggerFactory.getLogger(SdkPendingPaymentEngine::class.java)
    }

    override suspend fun sdkOwnsL1(): Boolean = sdkL1SendService.cutoverCommitted()

    override suspend fun hasObserved(txIdHex: String): Boolean =
        l1SendProbeService.observedTxContext(txIdHex) != null

    override suspend fun bridge(txIdHex: String, rawTx: ByteArray): Transaction? =
        when (val bridged = bridgedTransactionFactory.bridge(txIdHex, rawTx)) {
            is BridgedTxResult.Bridged -> bridged.transaction
            is BridgedTxResult.NotBridged -> {
                log.warn("pending payment {}: the wallet bridge did not commit it ({})", txIdHex, bridged.reason)
                null
            }
        }

    override suspend fun broadcast(reservation: SdkDeferredPayment) {
        try {
            when (val result = sdkL1SendService.broadcastDeferredPayment(reservation)) {
                is SdkWriteResult.Broadcast ->
                    log.info("pending payment {} broadcast via the SDK", result.value)
                is SdkWriteResult.NotBroadcast ->
                    log.warn(
                        "pending payment {}: SDK broadcast refused ({}); the payee's broadcast covers delivery",
                        reservation.txidHex, result.reason, result.cause
                    )
                is SdkWriteResult.Ambiguous ->
                    log.warn("pending payment {}: SDK broadcast outcome unconfirmed", reservation.txidHex, result.cause)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log.warn("pending payment {}: SDK broadcast failed", reservation.txidHex, e)
        }
    }

    override suspend fun release(reservation: SdkDeferredPayment) {
        try {
            sdkL1SendService.releaseDeferredPayment(reservation)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log.warn("pending payment {}: could not release the SDK reservation", reservation.txidHex, e)
        }
    }
}
