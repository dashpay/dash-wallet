package de.schildbach.wallet.ui.send

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.CreditBalanceInfo
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.service.platform.sdk.SdkAssetLockFundingPreflight
import de.schildbach.wallet.service.platform.work.PerformTopUpOperation
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Coin
import org.dashj.platform.dpp.identifier.Identifier
import org.slf4j.LoggerFactory
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
    private val platformRepo: PlatformRepo,
    private val identity: BlockchainIdentityConfig,
    private val assetLockFundingPreflight: SdkAssetLockFundingPreflight
) : ViewModel() {
    companion object {
        private val log = LoggerFactory.getLogger(BuyCreditsViewModel::class.java)
    }

    /**
     * The identity's CURRENT credit balance, expressed in Dash for display.
     *
     * Shown under the amount field so the user can see what they already hold
     * before deciding how much to buy. `null` while unknown — no identity yet,
     * or the balance could not be read — and the label is then hidden rather
     * than showing a misleading zero.
     *
     * Credits are 1/1000 of a duff ([CreditBalanceInfo.CREDITS_PER_DUFF]), so
     * the conversion to a Dash amount divides before building the Coin.
     */
    private val _identityBalance = MutableStateFlow<Coin?>(null)
    val identityBalance: StateFlow<Coin?> = _identityBalance.asStateFlow()

    init {
        refreshIdentityBalance()
    }

    fun refreshIdentityBalance() {
        viewModelScope.launch {
            val balance = withContext(Dispatchers.IO) {
                try {
                    val id = identity.get(BlockchainIdentityConfig.IDENTITY_ID) ?: return@withContext null
                    val info = platformRepo.getIdentityBalance(Identifier.from(id))
                    Coin.valueOf(info.balance / CreditBalanceInfo.CREDITS_PER_DUFF)
                } catch (e: Exception) {
                    // Best-effort display only: never let a balance read break the
                    // purchase screen.
                    log.info("could not read identity credit balance: {}", e.message)
                    null
                }
            }
            _identityBalance.value = balance
        }
    }

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
