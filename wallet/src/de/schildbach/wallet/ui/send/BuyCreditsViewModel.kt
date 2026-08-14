package de.schildbach.wallet.ui.send

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.work.WorkInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.service.platform.sdk.SdkAssetLockFundingPreflight
import de.schildbach.wallet.service.platform.sdk.SdkTransparentTopUp
import de.schildbach.wallet.service.platform.sdk.SdkWriteResult
import de.schildbach.wallet.service.platform.work.TopupIdentityOperation
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import de.schildbach.wallet.data.CreditBalanceInfo
import de.schildbach.wallet.data.WalletData
import org.dash.wallet.common.data.Resource
import org.dash.wallet.common.services.analytics.AnalyticsService
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import org.bitcoinj.core.Coin
import org.dashj.platform.dpp.identifier.Identifier
import org.slf4j.LoggerFactory
import javax.inject.Inject

@HiltViewModel
class BuyCreditsViewModel @Inject constructor(
    val walletApplication: WalletApplication,
    val platformRepo: PlatformRepo,
    val identity: BlockchainIdentityConfig,
    val walletDataProvider: WalletData,
    val analytics: AnalyticsService,
    val dashPayConfig: DashPayConfig,
    private val sdkTransparentTopUp: SdkTransparentTopUp,
    private val assetLockFundingPreflight: SdkAssetLockFundingPreflight
) : ViewModel() {
    companion object {
        private val log = LoggerFactory.getLogger(BuyCreditsViewModel::class.java)
    }

    var identityId: String? = null
    var topUpTransaction: Transaction? = null
    private val _currentWorkId = MutableStateFlow("")
    val currentWorkId: StateFlow<String>
        get() = _currentWorkId

    private suspend fun getNextWorkId() = withContext(Dispatchers.IO) {
        dashPayConfig.getTopupCounter().toString(16)
    }

    private val topupIdentityOperation = TopupIdentityOperation(walletApplication)

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

    fun topWorkStatus(workId: String): LiveData<Resource<WorkInfo>> {
        return TopupIdentityOperation.operationStatus(walletApplication, workId, analytics)
    }

    suspend fun topUpOnPlatform() = withContext(Dispatchers.IO) {
        identity.get(BlockchainIdentityConfig.IDENTITY_ID)?.let { identityId ->
            val workId = getNextWorkId()
            topupIdentityOperation
                .create(workId, topUpTransaction?.txId!!)
                .enqueue()
            _currentWorkId.value = workId
        }
    }

    suspend fun getTransaction(txId: Sha256Hash?) = withContext(Dispatchers.IO) {
        walletDataProvider.wallet!!.getTransaction(txId)
    }

    /**
     * Whether the cutover is committed. Post-cutover the dashj L1 engine is
     * HELD (0 UTXOs), so building the top-up asset lock with dashj fails —
     * the funds live in the SDK, and the go handler routes funding through
     * [topUpViaSdk] instead of the dashj asset-lock + [TopupIdentityWorker]
     * chain. Pre-cutover this is false and the existing dashj path is used
     * byte-for-byte.
     */
    suspend fun isCutoverCommitted(): Boolean = sdkTransparentTopUp.isCutoverCommitted()

    /**
     * Post-cutover top-up: fund the EXISTING identity's credit balance by
     * [amountDuffs] Core duffs (the user-entered amount) directly through the
     * SDK's resume-gated `topUpFromCore` (which FUSES the asset-lock build with
     * the Platform top-up registration — no dashj tx/txid). Returns the
     * three-valued outcome the go handler observes directly: Broadcast(new
     * credit balance) / NotBroadcast (nothing spent, retry-safe) / Ambiguous
     * (unconfirmed — never retried). Returns NotBroadcast when no identity id
     * is on record.
     */
    suspend fun topUpViaSdk(amountDuffs: Long): SdkWriteResult<Long> = withContext(Dispatchers.IO) {
        val identityId = identity.get(BlockchainIdentityConfig.IDENTITY_ID)
            ?: return@withContext SdkWriteResult.NotBroadcast("no identity to top up")
        sdkTransparentTopUp.topUp(identityId, amountDuffs)
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