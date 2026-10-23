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
import de.schildbach.wallet.service.platform.sdk.DashSdkService
import de.schildbach.wallet.service.platform.sdk.L1ShadowSyncService
import de.schildbach.wallet.service.platform.sdk.ShadowSyncPhase
import de.schildbach.wallet.service.platform.sdk.ShadowSyncProgress
import de.schildbach.wallet.service.platform.sdk.SdkBridgedTransactionFactory
import de.schildbach.wallet.service.platform.sdk.SdkDeferredPayment
import de.schildbach.wallet.service.platform.sdk.SdkL1SendService
import de.schildbach.wallet.service.platform.sdk.SdkWriteResult
import de.schildbach.wallet.service.platform.sdk.TX_CONTEXT_CHAIN_LOCKED
import de.schildbach.wallet.service.platform.sdk.TX_CONTEXT_INSTANT_SEND
import de.schildbach.wallet.service.platform.sdk.sdkOutpointBytes
import de.schildbach.wallet.service.platform.sdk.wireTxidBytesOrNull
import kotlinx.coroutines.CancellationException
import org.bitcoinj.core.Transaction
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/** What the SDK engine can say about one transaction. */
enum class EngineObservation {
    /** The engine holds a record of it, so it reached the network. */
    OBSERVED,

    /** No record, read from a running engine whose scan is caught up: real negative evidence. */
    ABSENT,

    /**
     * Nothing can be concluded: the store could not be read, or the engine is not running or not
     * caught up, so the lack of a record proves nothing. Must never count as [ABSENT].
     */
    UNAVAILABLE
}

/**
 * Whether the SDK store's row for a payment proves it reached the network (review 4309551133): an
 * InstantSend lock on it, or a block (chain-locked or not). A row with neither is only what this
 * wallet recorded, possibly for its own broadcast, and is no evidence of delivery. Pure.
 */
internal fun sdkRowIsNetworkEvidence(context: Int, blockHeight: Int): Boolean =
    context == TX_CONTEXT_INSTANT_SEND || context == TX_CONTEXT_CHAIN_LOCKED || blockHeight > 0

/**
 * Pure classification behind [PendingPaymentEngine.observation]. A record found is evidence
 * whatever the engine's state; the absence of one only counts when [live].
 *
 * @param recordFound whether the store holds a row for the transaction, or null when the store
 *   could not be read
 */
internal fun classifyEngineObservation(recordFound: Boolean?, live: Boolean): EngineObservation = when {
    // [recordFound]: a row with network evidence ([sdkRowIsNetworkEvidence]), not merely a row
    recordFound == true -> EngineObservation.OBSERVED
    recordFound == null -> EngineObservation.UNAVAILABLE
    !live -> EngineObservation.UNAVAILABLE
    else -> EngineObservation.ABSENT
}

/**
 * Whether the SDK engine is observing the network right now: running, past connecting, not in
 * error, and with its compact-filter scan caught up to the tip. Pure.
 */
/**
 * Whether the engine's silence about a transaction is evidence. [ShadowSyncProgress.scanCaughtUpToTip]
 * is not enough: it is a DISPLAY predicate, true while the filters are at the tip even when the
 * blocks they matched have not been processed yet ([ShadowSyncProgress.blockPipelineLagging]). A
 * payment mined in one of those blocks has no row until they are, so a missing row then proves
 * nothing.
 *
 * Absence therefore needs affirmative proof that transaction processing has reached the KNOWN
 * TIP, with no tolerance: the COMMITTED wallet cursor ([ShadowSyncProgress.walletSyncedHeight],
 * 0 = no evidence) at or past the best tip the engine knows ([ShadowSyncProgress.bestKnownTipHeight],
 * the larger of the header height and the header target, so a target left behind by headers that
 * have since moved on is not mistaken for the tip). One block short is one block whose payment may
 * have no row yet. An unknown tip is no evidence either.
 */
internal fun isEngineObservationLive(engineRunning: Boolean, progress: ShadowSyncProgress): Boolean {
    val tip = progress.bestKnownTipHeight
    return engineRunning &&
        progress.phase != ShadowSyncPhase.IDLE &&
        progress.phase != ShadowSyncPhase.CONNECTING &&
        progress.phase != ShadowSyncPhase.ERROR &&
        progress.scanCaughtUpToTip &&
        progress.headerTarget > 0 &&
        tip > 0 &&
        progress.walletSyncedHeight >= tip &&
        !progress.blockPipelineLagging
}

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
     * [sdkOwnsL1] for a decision that must fail closed: true or false when the cutover state was
     * read, null when it could not be. [sdkOwnsL1] reads an unreadable state as "not committed",
     * which is the unsafe answer for finishing an acknowledged payment (review 4306695898).
     */
    suspend fun sdkOwnsL1OrUnknown(): Boolean?

    /**
     * Whether the SDK engine holds a record of [txIdHex]. The engine only records a transaction it
     * has observed on the network, and a payment the wallet has not broadcast itself can only be
     * there because the payee broadcast it. [EngineObservation.UNAVAILABLE] when that cannot be
     * told: an unreadable store, or an engine that is not live (see [isObservationLive]).
     */
    suspend fun observation(txIdHex: String): EngineObservation

    /**
     * True while the engine is running and caught up, so that a transaction it has not recorded
     * is genuinely unseen. While false, no silence may be read as evidence that a payment was
     * never sent.
     */
    fun isObservationLive(): Boolean

    /**
     * An UPPER bound on the engine's current processed height, or null when the engine reports
     * none. Paired with [SdkDeferredPayment.reservedAtHeight] (a lower bound at reservation time)
     * it over-estimates how many blocks a reservation has aged, never under-estimates.
     */
    fun chainHeightUpperBound(): Long?

    /**
     * True when an input of [tx] is spent by a DIFFERENT transaction whose place in the chain is
     * FINAL, so [tx] can never confirm; false when the engine knows of no such spend, or of none
     * that is final yet; null when it cannot tell (store not open or unreadable).
     *
     * Inclusion in a block is not enough: a shallow reorganisation can take the spend out again
     * and make [tx] valid, after its recovery records were deleted. Final means the same as for
     * [ChainLockedCoinSelector]: in a chainlocked block, at or below the best known chainlock
     * height, or, when no chainlock height is known, [ChainLockedCoinSelector.FALLBACK_CONFIRMATIONS]
     * deep below the known tip. An unknown tip is not final.
     */
    suspend fun inputSpentByFinalConflict(tx: Transaction): Boolean?

    /**
     * Commits [rawTx] to the dashj wallet of record the way every SDK send is committed (the
     * bridge: `maybeCommitTx`, the confidence and purpose stamps, the dashj announce, the
     * self-spend grace marker and the send analytics), returning the wallet's live instance, or
     * null when the bridge could not commit it.
     */
    suspend fun bridge(txIdHex: String, rawTx: ByteArray, origin: org.bitcoinj.wallet.Wallet): Transaction?

    /** Consumes [reservation] by broadcasting its signed transaction. Best effort; never throws. */
    suspend fun broadcast(reservation: SdkDeferredPayment)

    /** Gives [reservation]'s inputs back to the engine. Best effort; never throws. */
    suspend fun release(reservation: SdkDeferredPayment)
}

@Singleton
class SdkPendingPaymentEngine @Inject constructor(
    private val sdkL1SendService: SdkL1SendService,
    private val sdkService: DashSdkService,
    private val l1ShadowSyncService: L1ShadowSyncService,
    private val bridgedTransactionFactory: SdkBridgedTransactionFactory
) : PendingPaymentEngine {
    companion object {
        private val log = LoggerFactory.getLogger(SdkPendingPaymentEngine::class.java)
    }

    override suspend fun sdkOwnsL1(): Boolean = sdkL1SendService.cutoverCommitted()

    override suspend fun sdkOwnsL1OrUnknown(): Boolean? = sdkL1SendService.cutoverCommittedOrUnknown()

    /**
     * The best chain tip the engine knows ([ShadowSyncProgress.bestKnownTipHeight]: the larger of
     * the header height and the header target), which no processing cursor can exceed. Null when
     * the tip is unknown: the committed wallet cursor is a lower bound on processing and says
     * nothing about the tip, so it is never used in its place.
     */
    override fun chainHeightUpperBound(): Long? = try {
        val progress = l1ShadowSyncService.progress.value
        progress.bestKnownTipHeight.takeIf { it > 0 }
            ?.let { maxOf(it, progress.filterHeight, progress.walletSyncedHeight) }
    } catch (e: Exception) {
        log.warn("could not read the SDK engine height", e)
        null
    }

    override suspend fun inputSpentByFinalConflict(tx: Transaction): Boolean? {
        return try {
            val db = sdkService.databaseOrNull() ?: return null
            val ownWireTxid = tx.txId.reversedBytes
            tx.inputs.any { input ->
                val outpoint = sdkOutpointBytes(input.outpoint.hash.reversedBytes, input.outpoint.index)
                val txo = db.txoDao().getByOutpoint(outpoint) ?: return@any false
                val spender = txo.spendingTxid
                    ?.takeIf { txo.isSpent && !it.contentEquals(ownWireTxid) }
                    ?: return@any false
                val row = db.transactionDao().getByTxid(spender) ?: return@any false
                row.context == TX_CONTEXT_CHAIN_LOCKED ||
                    ChainLockedCoinSelector.isChainLockedHeight(
                        appearedAtHeight = row.blockHeight,
                        chainLockHeight = l1ShadowSyncService.chainLockHeight.value,
                        // the validated header chain, never the peers' claimed target: an
                        // over-estimated tip would over-estimate the depth
                        bestChainHeight = l1ShadowSyncService.progress.value.headerHeight
                            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log.warn("could not read the SDK store for conflicting spends of {}", tx.txId, e)
            null
        }
    }

    override fun isObservationLive(): Boolean = try {
        isEngineObservationLive(l1ShadowSyncService.isEngineRunning(), l1ShadowSyncService.progress.value)
    } catch (e: Exception) {
        log.warn("could not read the SDK engine state; treating its observation as unavailable", e)
        false
    }

    /**
     * A strict read of the SDK store. Unlike L1SendProbeService.observedTxContext, which folds an
     * unreadable store into "no row", a failure here is reported as UNAVAILABLE. Never boots the
     * SDK: a store that is not open is simply unavailable.
     */
    override suspend fun observation(txIdHex: String): EngineObservation {
        val recordFound: Boolean? = try {
            val wire = wireTxidBytesOrNull(txIdHex)
            val db = sdkService.databaseOrNull()
            if (wire == null || db == null) {
                null
            } else {
                // A row is not delivery (review 4309551133): the engine records what it broadcast
                // itself. Only network evidence counts, as master's dashj confidence did.
                db.transactionDao().getByTxid(wire)?.let { sdkRowIsNetworkEvidence(it.context, it.blockHeight) } ?: false
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log.warn("could not read the SDK store for {}", txIdHex, e)
            null
        }
        return classifyEngineObservation(recordFound, isObservationLive())
    }

    override suspend fun bridge(txIdHex: String, rawTx: ByteArray, origin: org.bitcoinj.wallet.Wallet): Transaction? =
        when (val bridged = bridgedTransactionFactory.bridge(txIdHex, rawTx, originWallet = origin)) {
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
