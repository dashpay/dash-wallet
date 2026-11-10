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

package de.schildbach.wallet.service

import android.content.Context
import android.database.sqlite.SQLiteException
import dagger.hilt.android.qualifiers.ApplicationContext
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.dao.BlockchainStateDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bitcoinj.core.AbstractBlockChain
import org.bitcoinj.core.Block
import org.bitcoinj.core.BlockChain
import org.bitcoinj.core.CheckpointManager
import org.bitcoinj.core.Coin
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.PeerGroup
import org.bitcoinj.core.StoredBlock
import org.bitcoinj.store.BlockStoreException
import org.dash.wallet.common.Configuration
import de.schildbach.wallet.data.WalletData
import org.dash.wallet.common.data.entity.BlockchainState
import org.dash.wallet.common.data.NetworkStatus
import org.dash.wallet.common.data.SyncStage
import org.dash.wallet.common.data.entity.BlockchainState.Impediment
import org.dash.wallet.common.services.BlockchainStateProvider
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.util.EnumSet
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Blockchain state data provider
 *
 * @property blockchainStateDao
 * @property walletDataProvider is used to determine the network parameters and DashJ Context
 * @property configuration is used to save some information
 *
 */

@Singleton
class BlockchainStateDataProvider @Inject constructor(
    @ApplicationContext
    private val context: Context,
    private val dashSystemService: DashSystemService,
    private val blockchainStateDao: BlockchainStateDao,
    private val walletDataProvider: WalletData,
    private val configuration: Configuration
) : BlockchainStateProvider {
    companion object {
        /**
         *  NetworkParameters.TARGET_SPACING is 2.5 min, but the DGW algorithm targets 2.625 min
         *  See [org.bitcoinj.params.AbstractBitcoinNetParams.DarkGravityWave]
         */
        const val BLOCK_TARGET_SPACING = 2.625 * 60 // seconds
        const val DAYS_PER_YEAR = 365.242199
        const val SECONDS_PER_DAY = 24 * 60 * 60
        val MASTERNODE_COST: Coin = Coin.valueOf(1000, 0)
        const val MASTERNODE_COUNT = 3800
    }

    // this coroutineScope should execute all jobs sequentially
    private val coroutineScope = CoroutineScope(Executors.newSingleThreadExecutor().asCoroutineDispatcher())

    private val networkStatusFlow = MutableStateFlow(NetworkStatus.UNKNOWN)
    private val connectedPeerCountFlow = MutableStateFlow(0)
    private val syncStageFlow = MutableStateFlow<SyncStage?>(null)

    /**
     * The impediment set as last reported by the dashj service's
     * connectivity/storage/security monitors ([updateImpediments] /
     * [updateBlockchainState]) — those monitors keep running post-cutover
     * (they are Android-side, not peergroup-side), so they stay the source
     * of truth for connectivity while the SDK derivation
     * ([updateSdkBlockchainState]) contributes only the progress-stall
     * NETWORK bit. Both writers compose the row's impediments the same way
     * ([composeImpediments]); everything runs on the serial [coroutineScope].
     */
    private var serviceImpediments: Set<Impediment> = emptySet()

    /** Kill-list Step B: the SDK-derived progress-stall NETWORK impediment (post-cutover only). */
    private var sdkStallNetworkImpediment = false

    override suspend fun getState(): BlockchainState? {
        return blockchainStateDao.getState()
    }

    override fun observeState(): Flow<BlockchainState?> {
        return blockchainStateDao.observeState().distinctUntilChanged()
    }

    fun updateImpediments(impediments: Set<Impediment>) {
        coroutineScope.launch {
            serviceImpediments = impediments
            val blockchainState = blockchainStateDao.getState()
            if (blockchainState != null) {
                blockchainState.impediments.clear()
                blockchainState.impediments.addAll(composeImpediments())
                blockchainStateDao.saveState(blockchainState)
            }
        }
    }

    /**
     * Service-reported impediments plus the SDK-derived stall NETWORK bit.
     * Pre-cutover [sdkStallNetworkImpediment] is permanently false, so this
     * is exactly the service set — byte-identical to the pre-Step-B path.
     */
    private fun composeImpediments(): EnumSet<Impediment> {
        val impediments = EnumSet.noneOf(Impediment::class.java)
        impediments.addAll(serviceImpediments)
        if (sdkStallNetworkImpediment) {
            impediments.add(Impediment.NETWORK)
        }
        return impediments
    }

    /**
     * Kill-list Step B (sync-state track): apply one SDK-derived
     * [de.schildbach.wallet.service.platform.sdk.SdkBlockchainStateUpdate]
     * to the persisted row — the post-cutover replacement for
     * [updateBlockchainState], fed by
     * [de.schildbach.wallet.service.platform.sdk.SdkBlockchainStateService]'s
     * equality-gated poll of the SDK SPV progress. Null fields preserve the
     * row's current value (see the update class KDoc) — `chainlockHeight`
     * included, and it is additionally clamped MONOTONICALLY so the
     * engine's session-scoped lower bound can never regress a higher value
     * an earlier session (or dashj, pre-cutover) already established.
     * Serialized with every other writer on [coroutineScope].
     */
    internal fun updateSdkBlockchainState(update: de.schildbach.wallet.service.platform.sdk.SdkBlockchainStateUpdate) {
        coroutineScope.launch {
            sdkStallNetworkImpediment = update.networkStalled
            val blockchainState = blockchainStateDao.getState() ?: BlockchainState()
            update.bestChainHeight?.let {
                blockchainState.bestChainHeight = it
                // The ongoing-sync notification only dismisses when the row's
                // height reaches config.bestChainHeightEver (BlockchainServiceImpl),
                // and only dashj paths ever advanced that pref — frozen at
                // cutover. Mirror WalletApplication's maybe-increment write so
                // the SDK tip keeps it moving post-cutover.
                configuration.maybeIncrementBestChainHeightEver(it)
            }
            update.bestChainDateMs?.let { blockchainState.bestChainDate = java.util.Date(it) }
            update.mnListHeight?.let { blockchainState.mnlistHeight = it }
            // Chainlock height: the engine only knows the chainlocks it
            // applied THIS session (a lower bound — see
            // SdkBlockchainStateUpdate.chainlockHeight), so take the max
            // rather than assigning. A restart therefore never walks the
            // row backwards past what dashj or an earlier session proved.
            update.chainlockHeight?.let {
                if (it > blockchainState.chainlockHeight) {
                    blockchainState.chainlockHeight = it
                }
            }
            // Null percent (transient SDK ERROR) preserves the row's value —
            // a peer hiccup must not flap isSynced() consumers 100 → 0 → 100.
            update.percentageSync?.let { blockchainState.percentageSync = it }
            // Phase 1b item 7: on the SDK path `replaying` means "the engine is
            // still doing replay work", and it keeps the blockchain service alive
            // (idle rule guard + wake lock) until it is not. This used to be
            // hard-coded false, so nothing protected the post-upgrade replay
            // and the one-minute restart alarm never fired after an idle stop.
            //
            // Set from the LIFECYCLE signal, not the display percent (review,
            // 2026-09-23): percentageSync reads 100 at the iOS aggregate
            // threshold while the committed cursor is thousands of blocks
            // behind and the block pipeline still lags, and deriving the flag
            // from it let a final-batch park drop the idle-stop guard and the
            // wake lock with the engine mid-work. A null (transient ERROR /
            // IDLE / CONNECTING) leaves the flag as it was. The DAO's
            // clear-at-100% is opt-in and only dashj's scan-progress writer
            // opts in, so neither this write nor an impediment-only rewrite
            // of the row can undo the separation.
            update.replayComplete?.let { blockchainState.replaying = !it }
            blockchainState.impediments = composeImpediments()
            blockchainStateDao.saveState(blockchainState)
            // A caught-up snapshot must not REGRESS a COMPLETE stage — that is the
            // "syncing 100%" blip (see mayPreserveEstablishedSyncStage): a live
            // shadow SPV never latches SYNCED, so a FILTERS snapshot that has caught
            // up to the tip would otherwise drag a finished row back to BLOCKS.
            //
            // COMPLETE is the ONLY stage worth holding. Preserving whatever happened
            // to be there pinned the wallet OFFLINE across a reconnect: IDLE,
            // CONNECTING and ERROR all map to SyncStage.OFFLINE and all WRITE it
            // (shadowSyncPercent reports 0 for them, so they never set the preserve
            // flag), and the caught-up FILTERS snapshot that follows recovery does
            // set it — so the forward transition OFFLINE → BLOCKS was suppressed and
            // the wallet read as offline while its scan was active and at the tip.
            // A null flow is likewise not something to preserve: holding the write
            // would leave getSyncStage() reporting OFFLINE for a caught-up wallet at
            // process start. Both fall out of comparing against COMPLETE.
            if (!update.preserveEstablishedSyncStage || syncStageFlow.value != SyncStage.COMPLETE) {
                syncStageFlow.value = update.syncStage
            }
        }
    }

    /**
     * Kill-list Step B: the cutover-ROLLBACK hook, invoked by
     * [de.schildbach.wallet.service.platform.sdk.SdkBlockchainStateService]
     * when the cutover gate transitions true → false. The derivation
     * coroutine is already cancelled at that point, but state it wrote
     * would otherwise latch: a raised [sdkStallNetworkImpediment] would keep
     * composing [org.dash.wallet.common.data.entity.BlockchainState.Impediment.NETWORK]
     * into every later write (syncFailed() forever), and the SDK-written
     * [syncStageFlow] value would mask dashj's until its next update. Resets
     * both — recomposing the persisted row's impediments so an
     * already-persisted NETWORK bit clears too — and lets the resumed dashj
     * pipeline re-drive everything else. Serialized on [coroutineScope].
     */
    internal fun clearSdkDerivedState() {
        coroutineScope.launch {
            sdkStallNetworkImpediment = false
            syncStageFlow.value = null
            val blockchainState = blockchainStateDao.getState()
            if (blockchainState != null) {
                blockchainState.impediments.clear()
                blockchainState.impediments.addAll(composeImpediments())
                blockchainStateDao.saveState(blockchainState)
            }
        }
    }

    fun updateBlockchainState(blockChain: BlockChain, impediments: Set<Impediment>, percentageSync: Int, syncStage: PeerGroup.SyncStage?) {
        coroutineScope.launch {
            var blockchainState = blockchainStateDao.getState()
            if (blockchainState == null) {
                blockchainState = BlockchainState()
            }
            val chainHead: StoredBlock = blockChain.chainHead
            val chainLockHeight = dashSystemService.system.chainLockHandler.bestChainLockBlockHeight
            val mnListHeight: Int =
                dashSystemService.system.masternodeListManager.listAtChainTip.height.toInt()
            serviceImpediments = impediments
            blockchainState.bestChainDate = chainHead.header.time
            blockchainState.bestChainHeight = chainHead.height
            blockchainState.impediments = composeImpediments()
            blockchainState.chainlockHeight = chainLockHeight
            blockchainState.mnlistHeight = mnListHeight
            blockchainState.percentageSync = percentageSync
            // The one writer whose percent IS the scan position: dashj's own
            // download progress ends a replay at 100%.
            blockchainStateDao.saveState(blockchainState, clearReplayAtHundredPercent = true)
            syncStageFlow.value = syncStage?.toNeutral()
        }
    }

    private fun PeerGroup.SyncStage.toNeutral(): SyncStage = when (this) {
        PeerGroup.SyncStage.OFFLINE -> SyncStage.OFFLINE
        PeerGroup.SyncStage.HEADERS -> SyncStage.HEADERS
        PeerGroup.SyncStage.MNLIST -> SyncStage.MNLIST
        PeerGroup.SyncStage.PREBLOCKS -> SyncStage.PREBLOCKS
        PeerGroup.SyncStage.BLOCKS -> SyncStage.BLOCKS
        PeerGroup.SyncStage.COMPLETE -> SyncStage.COMPLETE
    }

    fun resetBlockchainState() {
        coroutineScope.launch {
            blockchainStateDao.saveState(
                BlockchainState(true)
            )
        }
    }

    /**
     * Phase 1b item 8 (docs/upgrade-memory-and-sync-plan.md): the cutover
     * just handed L1 to the SDK on an EXISTING (upgraded) wallet, so the SDK
     * is about to scan from the wallet's birth height while the row still
     * carries dashj's "synced, 100%". Mark the replay as started NOW — before
     * the SDK's first progress update, which can trail the commit by minutes
     * — so `isSynced()` reads false (the home screen shows syncing rather than
     * dashj's stale 100%, emulator finding 3), the idle rule keeps the
     * service alive (item 7), and the one-minute restart alarm applies
     * (item 9). Only restore and rescan used to set the flag.
     *
     * Heights, dates and impediments are preserved; the percentage is zeroed
     * because the SDK has scanned nothing yet — its first update replaces it
     * with the real figure. Idempotent: a row already mid-replay is left
     * alone. Never throws.
     */
    fun markReplayStartedForSdkTakeover() {
        coroutineScope.launch {
            val state = try {
                blockchainStateDao.getState()
            } catch (ex: SQLiteException) {
                null
            } ?: BlockchainState()
            if (state.replaying && state.percentageSync < 100) return@launch
            state.replaying = true
            state.percentageSync = 0
            blockchainStateDao.saveState(state)
            // The STAGE has to move with the row, in this same serialized job.
            //
            // isSynced() is fixed by the replaying flag above, but getSyncStage()
            // reads syncStageFlow, and dashj typically leaves that at COMPLETE —
            // it had finished before the cutover handed the wallet over. Nothing
            // corrects it until the SDK's first progress update lands, so every
            // syncStage consumer (MainViewModel.observeSyncStage) reads "finished"
            // over a row that says "replaying from 0%".
            //
            // BLOCKS, not null: null reads as OFFLINE (see getSyncStage), which
            // would claim the opposite falsehood — disconnected rather than done
            // — and a takeover replay IS a block scan. A real stage from the SDK
            // replaces this on the first update, and it cannot be held back by
            // the COMPLETE-preservation rule above precisely because it is no
            // longer COMPLETE.
            syncStageFlow.value = SyncStage.BLOCKS
        }
    }

    fun resetBlockchainSyncProgress() {
        coroutineScope.launch {
            val blockchainState: BlockchainState? = try {
                blockchainStateDao.getState()
            } catch (ex: SQLiteException) {
                null
            }
            if (blockchainState != null) {
                blockchainState.percentageSync = 0
                blockchainStateDao.saveState(blockchainState)
            }
        }
    }

    fun setNetworkStatus(networkStatus: NetworkStatus) {
        coroutineScope.launch {
            networkStatusFlow.emit(networkStatus)
        }
    }

    override fun getNetworkStatus(): NetworkStatus {
        return networkStatusFlow.value
    }

    // set directly rather than through the queue: callers read this to decide whether there is
    // live network evidence, so it must never lag behind the peer group
    fun setConnectedPeerCount(peerCount: Int) {
        connectedPeerCountFlow.value = peerCount
    }

    override fun getConnectedPeerCount(): Int {
        return connectedPeerCountFlow.value
    }

    override fun observeConnectedPeerCount(): Flow<Int> {
        return connectedPeerCountFlow
    }

    override fun observeNetworkStatus(): Flow<NetworkStatus> {
        return networkStatusFlow
    }

    override fun observeSyncStage(): Flow<SyncStage?> {
        return syncStageFlow
    }

    override fun getSyncStage(): SyncStage {
        return syncStageFlow.value ?: SyncStage.OFFLINE
    }
}
