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
package de.schildbach.wallet.service.platform.work

import android.app.Application
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.service.platform.sdk.SdkTransparentTopUp
import de.schildbach.wallet.service.platform.sdk.SdkWriteResult
import de.schildbach.wallet.service.platform.sdk.REASON_PRE_BROADCAST_ASSET_LOCK_SELECTION
import de.schildbach.wallet.service.platform.sdk.REASON_PRE_BROADCAST_BUILD_SHORTFALL
import de.schildbach.wallet.service.platform.sdk.SdkAssetLockFundingPreflight
import de.schildbach.wallet.service.work.BaseWorker
import de.schildbach.wallet.ui.shielded.assetLockMaxFeeReserve
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * Runs ONE user-initiated Buy Credits top-up through the SDK
 * ([SdkTransparentTopUp]), detached from the screen's lifecycle — a lock
 * screen, rotation, or process death cannot cancel the purchase mid-flight
 * (the old dashj flow had this via TopupIdentityWorker; this is its
 * SDK-only successor). Input is the AMOUNT ONLY: no wallet password and no
 * transaction id are stored in WorkManager's database — the SDK signs via
 * its own key resolver.
 *
 * Funds safety on reruns: if the process dies mid-call, WorkManager reruns
 * this worker; [SdkTransparentTopUp]'s resume gate then matches the
 * already-broadcast lock (by the identity's registration index) and
 * completes IT instead of building a second one — no double pay. This
 * worker itself never returns retry: a NotBroadcast outcome is the user's
 * to retry, and an Ambiguous outcome must never be blindly re-run — it is
 * handed to [ResumeTopUpsWorker], which resumes only the tracked lock.
 */
@HiltWorker
class PerformTopUpWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val sdkTransparentTopUp: SdkTransparentTopUp,
    private val blockchainIdentityConfig: BlockchainIdentityConfig,
    private val assetLockFundingPreflight: SdkAssetLockFundingPreflight
) : BaseWorker(context, parameters) {
    companion object {
        private val log = LoggerFactory.getLogger(PerformTopUpWorker::class.java)
        const val KEY_AMOUNT_DUFFS = "PerformTopUpWorker.AMOUNT_DUFFS"

        /** The purchase is a MAX ("spend everything") — see the class doc. */
        const val KEY_IS_MAX_SPEND = "PerformTopUpWorker.IS_MAX_SPEND"
        const val KEY_NEW_BALANCE = "PerformTopUpWorker.NEW_BALANCE"
        const val KEY_AMBIGUOUS = "PerformTopUpWorker.AMBIGUOUS"

        /** Progress marker: the SDK call that does the actual work has begun. */
        const val KEY_SDK_CALL_STARTED = "PerformTopUpWorker.SDK_CALL_STARTED"

        /**
         * Platform's minimum for an IdentityTopUp asset lock, in duffs:
         * identity_topup_base_cost (500) + the 50,000-duff processing floor —
         * the same figure the SDK FFI enforces as MIN_TOP_UP_DUFFS. A MAX
         * retry adjusted below this would broadcast a lock Core accepts but
         * Platform deterministically rejects, stranding the balance.
         */
        const val PLATFORM_TOP_UP_FLOOR_DUFFS = 50_500L
    }

    override suspend fun doWorkWithBaseProgress(): Result {
        val amountDuffs = inputData.getLong(KEY_AMOUNT_DUFFS, -1L)
        if (amountDuffs <= 0L) {
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "missing or invalid amount"))
        }
        val identityId = blockchainIdentityConfig.get(BlockchainIdentityConfig.IDENTITY_ID)
        if (identityId.isNullOrEmpty()) {
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "no identity to top up"))
        }

        val isMaxSpend = inputData.getBoolean(KEY_IS_MAX_SPEND, false)
        // What actually went out — the adjusted retry overwrites this, so the
        // success log names the sent amount, not the requested one.
        var sentDuffs = amountDuffs

        var result = try {
            // Tell the UI the hand-off is complete: the purchase is now the
            // SDK's (and this worker's) responsibility, not the screen's.
            setProgress(workDataOf(KEY_SDK_CALL_STARTED to true))
            sdkTransparentTopUp.topUp(identityId, amountDuffs)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.error("top-up threw unexpectedly", t)
            SdkWriteResult.Ambiguous(t)
        }

        // MAX-spend fee convergence — the shielded Internal Transfer rule
        // (ShieldedTransferExecutor.submit), applied to the top-up asset
        // lock: the exact L1 fee is unknowable app-side, so a MAX purchase
        // submits the FULL balance first and, when that fails the
        // provably-pre-broadcast coin selection (nothing submitted, the
        // selection released), retries ONCE with an ESTIMATED fee reserve
        // withheld, sized from the wallet's spendable UTXO count. An
        // over-reserve is lossless — the asset-lock builder returns the
        // excess as change. One shot only: the retry result never
        // re-adjusts. The adjusted amount must stay above Platform's
        // top-up floor, or the retry would strand a lock Platform rejects.
        // Match the CLASSIFIER's verdict, not a raw engine message: the
        // top-up build surfaces its shortfall as key-wallet's builder text
        // ("Coin selection error: Insufficient funds…"), which
        // classifyBroadcastFailure already folds — together with the
        // shielded path's "asset lock coin selection is short" shape — into
        // these two named, provably-pre-broadcast, retryable reasons.
        // (Matching only the shielded message here is the bug that made the
        // first live MAX test fail without ever retrying.)
        val first = result
        val retryableShortfall = first is SdkWriteResult.NotBroadcast &&
            (
                first.reason == REASON_PRE_BROADCAST_BUILD_SHORTFALL ||
                    first.reason == REASON_PRE_BROADCAST_ASSET_LOCK_SELECTION
                )
        if (isMaxSpend && retryableShortfall) {
            // The input population comes from the SDK's OWN eligible-UTXO
            // query — the same table its coin selection reads — not dashj's
            // spendableUtxoCount(): dashj counts coins the asset lock can
            // never select (CoinJoin, other accounts, non-final) and can be
            // stale post-cutover. Null = no evidence; adjust nothing.
            val utxoCount = assetLockFundingPreflight.eligibleAssetLockUtxoCountOrNull()
            if (utxoCount == null) {
                log.warn("max top-up: eligible UTXO count unavailable — not auto-adjusting")
            }
            val reserve = utxoCount?.let(::assetLockMaxFeeReserve)
            val adjusted = reserve?.let { amountDuffs - it.duffs }
            // The FFI floor is INCLUSIVE (`amount < MIN_TOP_UP_DUFFS` rejects),
            // so exactly 50,500 duffs is a valid retry.
            if (adjusted != null && adjusted >= PLATFORM_TOP_UP_FLOOR_DUFFS) {
                sentDuffs = adjusted
                log.info(
                    "max top-up auto-adjusting for the L1 asset-lock fee: requested {} duffs, " +
                        "reserve {} duffs ({} UTXOs), retrying once with {}",
                    amountDuffs,
                    reserve.duffs,
                    utxoCount,
                    adjusted
                )
                result = try {
                    sdkTransparentTopUp.topUp(identityId, adjusted)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    log.error("adjusted max top-up threw unexpectedly", t)
                    SdkWriteResult.Ambiguous(t)
                }
            } else if (adjusted != null) {
                log.warn(
                    "max top-up not auto-adjusting: {} duffs after the fee reserve is " +
                        "below Platform's {}-duff top-up floor",
                    adjusted,
                    PLATFORM_TOP_UP_FLOOR_DUFFS
                )
            }
        }
        return when (result) {
            is SdkWriteResult.Broadcast -> {
                log.info("top-up of {} duffs credited; new balance {}", sentDuffs, result.value)
                Result.success(workDataOf(KEY_NEW_BALANCE to result.value))
            }
            is SdkWriteResult.NotBroadcast -> {
                log.warn("top-up not sent: {}", result.reason)
                Result.failure(workDataOf(KEY_ERROR_MESSAGE to result.reason))
            }
            is SdkWriteResult.Ambiguous -> {
                // The lock, if broadcast, is Rust-tracked — the recovery
                // worker completes it; never re-run the purchase itself.
                ResumeTopUpsOperation(applicationContext as Application).enqueue()
                log.error("top-up outcome unconfirmed; recovery worker enqueued", result.cause)
                Result.failure(
                    workDataOf(
                        KEY_ERROR_MESSAGE to (result.cause.message ?: "top-up outcome unconfirmed"),
                        KEY_AMBIGUOUS to true
                    )
                )
            }
        }
    }
}
