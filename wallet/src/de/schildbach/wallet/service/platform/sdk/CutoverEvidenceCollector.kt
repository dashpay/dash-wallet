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

package de.schildbach.wallet.service.platform.sdk

import android.os.SystemClock
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.CancellationException
import org.bitcoinj.core.TransactionConfidence
import org.dash.wallet.common.WalletDataProvider
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 5d: assembles [CutoverEvidence] from the live services for
 * [evaluateCutoverReadiness]. THIN by design — every judgment lives in
 * the pure evaluator; this class only reads. Read failures degrade to
 * the CONSERVATIVE value (the one that blocks), never the permissive
 * one: a cutover must be provably safe, not assumed safe.
 */
@Singleton
class CutoverEvidenceCollector @Inject constructor(
    private val l1ShadowSyncService: L1ShadowSyncService,
    private val shieldedBalanceService: ShieldedBalanceService,
    private val dashPayConfig: DashPayConfig,
    private val identityConfig: BlockchainIdentityConfig,
    private val walletDataProvider: WalletDataProvider,
    private val walletApplication: WalletApplication
) {
    suspend fun collect(): CutoverEvidence {
        val shieldedEnabled = try {
            dashPayConfig.get(DashPayConfig.USE_KOTLIN_SDK_SHIELDED) == true
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("cutover evidence: flag read failed; assuming shielded ON (conservative)", t)
            true
        }
        val shieldedReady =
            shieldedBalanceService.shieldedSyncStatus.value == ShieldedSyncStatus.READY

        // Null count = UNKNOWN → conservatively counts as one pending lock
        // (blocks). Only consulted when the feature is on; when off the
        // evaluator ignores shielded state entirely, so 0 is honest.
        val pendingLocks = if (!shieldedEnabled) {
            0
        } else {
            shieldedBalanceService.pendingWalletShieldLockCount() ?: 1
        }

        // Mempool-only self-authored dashj txs: PENDING confidence with
        // source SELF — the class an SDK rescan cannot reproduce until
        // mined. Wallet unavailable → conservative 1.
        val unconfirmedSelfAuthored = try {
            walletDataProvider.wallet?.pendingTransactions?.count { tx ->
                tx.confidence?.source == TransactionConfidence.Source.SELF
            } ?: 1
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("cutover evidence: pending-tx read failed; assuming 1 (conservative)", t)
            1
        }

        // The legacy identity machine is mid-flight for anything past NONE
        // that has not reached DONE — including VOTING, which only the
        // legacy machine drives today. Read failure → conservative true.
        val identityInFlight = try {
            val state = IdentityCreationState.valueOf(
                identityConfig.get(BlockchainIdentityConfig.CREATION_STATE)
                    ?: IdentityCreationState.NONE.name
            )
            state != IdentityCreationState.NONE &&
                state.ordinal < IdentityCreationState.DONE.ordinal
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("cutover evidence: identity state read failed; assuming in-flight (conservative)", t)
            true
        }

        val backupExists = try {
            walletApplication
                .getFileStreamPath(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF)
                .exists()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            false
        }

        return CutoverEvidence(
            parityObservations = l1ShadowSyncService.parityStreakRecorder.snapshot(),
            unconfirmedSelfAuthoredTxs = unconfirmedSelfAuthored,
            identityOperationInFlight = identityInFlight,
            pendingShieldedLocks = pendingLocks,
            shieldedEnabled = shieldedEnabled,
            shieldedReady = shieldedReady,
            walletBackupExists = backupExists,
            nowElapsedMillis = SystemClock.elapsedRealtime()
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(CutoverEvidenceCollector::class.java)
    }
}
