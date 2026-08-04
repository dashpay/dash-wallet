package de.schildbach.wallet.ui.send

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.work.WorkInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.service.platform.sdk.SdkAssetLockFundingPreflight
import de.schildbach.wallet.service.platform.work.PerformTopUpOperation
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
     * UI from [topUpWorkStatus].
     */
    fun startTopUp(amountDuffs: Long) {
        PerformTopUpOperation(walletApplication).enqueue(amountDuffs)
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
    suspend fun canFundTopUp(amountDuffs: Long): Boolean = withContext(Dispatchers.IO) {
        assetLockFundingPreflight.canFundAssetLockDuffs(amountDuffs) ?: true
    }
}
