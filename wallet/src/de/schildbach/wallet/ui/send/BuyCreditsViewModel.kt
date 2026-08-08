package de.schildbach.wallet.ui.send

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.work.WorkInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.service.platform.sdk.ASSET_LOCK_PREFLIGHT_FEE_HEADROOM_DUFFS
import de.schildbach.wallet.service.platform.sdk.SdkAssetLockFundingPreflight
import de.schildbach.wallet.service.platform.work.PerformTopUpOperation
import de.schildbach.wallet.ui.shielded.assetLockMaxFeeReserve
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Buy Credits is SDK-only (Phase 2/3, MO-998): the dashj purchase path and
 * its TopupIdentityWorker/topup-counter plumbing are deleted. The purchase
 * runs as UNIQUE background work ([startTopUp] →
 * [de.schildbach.wallet.service.platform.work.PerformTopUpWorker]) so a
 * lock screen / rotation / process death cannot cancel it mid-flight;
 * interrupted attempts are completed by
 * [de.schildbach.wallet.service.platform.work.ResumeTopUpsWorker].
 */
@HiltViewModel
class BuyCreditsViewModel @Inject constructor(
    private val walletApplication: WalletApplication,
    private val assetLockFundingPreflight: SdkAssetLockFundingPreflight
) : ViewModel() {

    /**
     * Start the purchase as unique background work. A tap while one runs
     * attaches to the existing run (no double buy). The screen drives its
     * UI from [topUpWorkStatus]. [isMaxSpend] marks a "spend everything"
     * purchase: the worker may make ONE fee-adjusted retry when the full
     * balance fails the asset-lock coin selection pre-broadcast.
     */
    fun startTopUp(amountDuffs: Long, isMaxSpend: Boolean) {
        PerformTopUpOperation(walletApplication).enqueue(amountDuffs, isMaxSpend)
    }

    /** Live status of the unique purchase work (empty until first use). */
    fun topUpWorkStatus(): LiveData<List<WorkInfo>> =
        PerformTopUpOperation.status(walletApplication)

    /** Forget finished runs so an old outcome cannot re-fire on re-entry. */
    fun pruneTopUpWork() {
        PerformTopUpOperation(walletApplication).prune()
    }

    /**
     * PRE-FLIGHT funding-eligibility for an SDK top-up of [amountDuffs]:
     * would the asset-lock coin selection (final — confirmed/IS-locked —
     * BIP44 coins only) actually find the funds? Blocks the go handler
     * BEFORE the spend attempt when a display balance backed by non-final
     * or out-of-account outputs cannot fund the lock. Fail-OPEN: true when
     * the preflight has no evidence (pre-cutover, SDK unavailable, read
     * failure) — the real build stays the authority.
     */
    suspend fun canFundTopUp(amountDuffs: Long, isMaxSpend: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            // A MAX spend can never clear the preflight at the FULL balance —
            // the check demands fee headroom ON TOP of the amount, and there is
            // nothing on top of everything. Preflight what the worker's
            // fee-adjusted retry would actually send instead: the same reserve
            // rule the shielded Internal Transfer max uses
            // (assetLockMaxFeeReserve, sized from the spendable UTXO count).
            val effective = if (isMaxSpend) {
                // Same source the worker's retry uses: the SDK's own
                // eligible-UTXO count. The preflight re-adds its fixed fee
                // headroom to whatever it is asked about, and for a MAX spend
                // the withheld reserve IS the fee allowance — so subtract the
                // headroom here too, or the two stack and a MAX preflight
                // (eligible == amount) can never pass. Net effect: the check
                // becomes eligible + reserve >= amount, i.e. "is no more than
                // the fee reserve tied up in non-final coins".
                val count = assetLockFundingPreflight.eligibleAssetLockUtxoCountOrNull()
                val reserve = count?.let { assetLockMaxFeeReserve(it).duffs } ?: 0L
                (amountDuffs - reserve - ASSET_LOCK_PREFLIGHT_FEE_HEADROOM_DUFFS)
                    .coerceAtLeast(1L)
            } else {
                amountDuffs
            }
            assetLockFundingPreflight.canFundAssetLockDuffs(effective) ?: true
        }
}
