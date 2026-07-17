/*
 * Copyright 2020 Dash Core Group.
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
package de.schildbach.wallet.ui.invite

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Environment
import android.view.View
import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.database.dao.InvitationsDao
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.database.entity.Invitation
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.service.platform.sdk.SdkShieldedInviteCreation
import de.schildbach.wallet.service.platform.sdk.SdkWriteResult
import de.schildbach.wallet.service.platform.sdk.ShieldedBalanceService
import de.schildbach.wallet.service.platform.sdk.ShieldedSyncStatus
import de.schildbach.wallet.ui.dashpay.BaseProfileViewModel
import de.schildbach.wallet.ui.dashpay.work.SendInviteOperation
import de.schildbach.wallet.ui.dashpay.work.SendInviteStatusLiveData
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Context
import org.bitcoinj.wallet.AuthenticationKeyChain
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject

/**
 * The balance the invitation-fee dialog requires the user to HOLD for the
 * currently selected username kind, given the funding source (host-JVM
 * unit-testable). Drives both the "Confirm and pay" gate ([inviteFeeGate])
 * and the insufficiency message.
 *
 * - Private invite ([shielded] == true): the Type-20 exit denomination the
 *   invite withdraws from the pool — 0.3 contested / 0.1 non-contested — so
 *   the payment validation matches the amount shown on the tiles and the
 *   confirm screen (not the padded 0.15/0.35 pool fund-minimum).
 * - L1 invite: the L1 fee — [Constants.DASH_PAY_FEE_CONTESTED] (0.25)
 *   contested / [Constants.DASH_PAY_FEE] (0.03) non-contested.
 */
internal fun inviteFeeRequirement(shielded: Boolean, contestedSelected: Boolean): Coin {
    return if (shielded) {
        if (contestedSelected) SHIELDED_INVITE_CONTESTED else SHIELDED_INVITE_NON_CONTESTED
    } else {
        if (contestedSelected) Constants.DASH_PAY_FEE_CONTESTED else Constants.DASH_PAY_FEE
    }
}

/** Type-20 exit denominations withdrawn from the shielded pool for an invite. */
private val SHIELDED_INVITE_NON_CONTESTED: Coin = Coin.parseCoin("0.1")
private val SHIELDED_INVITE_CONTESTED: Coin = Coin.parseCoin("0.3")

/**
 * Pure "Confirm and pay" gate for the invitation-fee dialog (host-JVM
 * unit-testable — follows the `inviteShieldedOptions` /
 * `usernameSubmitButtonState` helper pattern). BOTH username-kind tiles stay
 * selectable regardless of balance (Fix G2 — the user must be able to tap
 * either); only this button gate reads the balance, for the CURRENTLY
 * selected kind's [inviteFeeRequirement].
 *
 * - L1 invite ([shielded] == false): enabled once the L1 wallet holds the
 *   selected fee.
 * - Private invite ([shielded] == true): funded from the SHIELDED POOL, so
 *   gate on the pool, not L1. A mid-sync shielded balance is a `Dash.ZERO`
 *   placeholder, NOT evidence of an empty pool — while [shieldedReady] is
 *   false the balance is UNKNOWN and the button stays disabled (never
 *   enabled off a stale-looking balance).
 */
internal fun inviteFeeGate(
    shielded: Boolean,
    l1Balance: Coin,
    shieldedReady: Boolean,
    shieldedBalance: Coin,
    contestedSelected: Boolean
): Boolean {
    val requirement = inviteFeeRequirement(shielded, contestedSelected)
    return if (shielded) {
        shieldedReady && shieldedBalance >= requirement
    } else {
        l1Balance >= requirement
    }
}

@ExperimentalCoroutinesApi
@HiltViewModel
open class InvitationFragmentViewModel @Inject constructor(
    private val walletApplication: WalletApplication,
    private val analytics: AnalyticsService,
    private val platformRepo: PlatformRepo,
    private val invitationDao: InvitationsDao,
    private val identityRepository: IdentityRepository,
    private val sdkShieldedInviteCreation: SdkShieldedInviteCreation,
    private val shieldedBalanceService: ShieldedBalanceService,
    blockchainIdentityDataDao: BlockchainIdentityConfig,
    dashPayProfileDao: DashPayProfileDao
) : BaseProfileViewModel(blockchainIdentityDataDao, dashPayProfileDao) {
    private val log = LoggerFactory.getLogger(InvitationFragmentViewModel::class.java)
    private val workerJob = Job()
    private val workerScope = CoroutineScope(workerJob + Dispatchers.IO)
    private val authExtension = platformRepo.authenticationGroupExtension!!

    private val pubkeyHash: ByteArray
        get() = authExtension.currentKey(AuthenticationKeyChain.KeyChainType.INVITATION_FUNDING).pubKeyHash

    private val fundingAddress: String
        get() = Address.fromPubKeyHash(walletApplication.wallet!!.params, pubkeyHash).toBase58()

    val sendInviteStatusLiveData = SendInviteStatusLiveData(walletApplication, fundingAddress)

//    val dynamicLinkData
//        get() = sendInviteStatusLiveData.value!!.data!!.dynamicLink

    val shortDynamicLinkData
        get() = sendInviteStatusLiveData.value!!.data!!.shortDynamicLink

    val walletData
        get() = walletApplication

    /**
     * SHIELDED-pool balance/sync accessors for the invitation-fee dialog's
     * private-invite gate (Fix F). A private invite funds its fee from the
     * pool, so the dialog must read the pool — not the (now-low) L1 balance —
     * to decide whether contested is selectable. Mirrors how
     * [InviteShieldedFundingViewModel] sources them: bring the runtime up
     * (idempotent), then observe the balance flow and the sync status.
     */
    val shieldedSyncStatus: StateFlow<ShieldedSyncStatus>
        get() = shieldedBalanceService.shieldedSyncStatus

    fun observeShieldedBalance(): Flow<Dash> = shieldedBalanceService.observeShieldedBalance()

    /** Bring up the shielded runtime so its balance loads / status advances. */
    fun ensureShieldedReady() {
        viewModelScope.launch {
            runCatching { shieldedBalanceService.ensureShieldedReady() }
                .onFailure { log.warn("shielded bring-up failed", it) }
        }
    }

    suspend fun sendInviteTransaction(value: Coin): String {
        // ensure that the fundingAddress hasn't been used
        withContext(Dispatchers.IO) {
            Context.propagate(walletData.wallet!!.context)
            var currentInvitation: Invitation?
            do {
                currentInvitation = invitationDao.loadByFundingAddress(fundingAddress)
                if (currentInvitation?.txid != null) {
                    authExtension.freshKey(AuthenticationKeyChain.KeyChainType.INVITATION_FUNDING)
                }
            } while (currentInvitation?.txid != null)
        }
        val fundingAddress = this.fundingAddress // save the address locally
        SendInviteOperation(walletApplication)
            .create(fundingAddress, value)
            .enqueue()
        return fundingAddress
    }

    /**
     * The shielded (L2) invitation link, once created. Null until a shielded
     * invite is successfully funded — the shielded path has no WorkManager
     * output (the L1 [sendInviteStatusLiveData] source), so the created-invite
     * screen reads the link from here for the shielded branch.
     */
    private val _shieldedInviteLink = MutableStateFlow<InvitationLinkData?>(null)
    val shieldedInviteLink: StateFlow<InvitationLinkData?>
        get() = _shieldedInviteLink

    /**
     * The share/copy link for a created shielded invite — the AppsFlyer
     * OneLink wrapping the deep link (H1), or the raw deep link when OneLink
     * generation was unavailable. The created-invite screen shares THIS, not
     * the raw [shieldedInviteLink] deep link (which stays the preview source).
     */
    private val _shieldedInviteShareLink = MutableStateFlow<String?>(null)
    val shieldedInviteShareLink: StateFlow<String?>
        get() = _shieldedInviteShareLink

    /**
     * Fund a SHIELDED (L2) invitation directly from the shielded pool — the
     * private-invitation counterpart of [sendInviteTransaction]. [contested]
     * (derived from the fee the inviter picked) selects the exit denomination.
     * On success the funded deep link is published to [shieldedInviteLink] and
     * its shareable OneLink to [shieldedInviteShareLink]. Runs the ~30s proof
     * off the main thread.
     */
    suspend fun createShieldedInvite(contested: Boolean): SdkWriteResult<InvitationLinkData> =
        withContext(Dispatchers.IO) {
            val profile = identityRepository.getLocalUserProfile()
                ?: return@withContext SdkWriteResult.NotBroadcast("no local user profile")
            when (val result = sdkShieldedInviteCreation.createShieldedInvite(
                username = profile.username,
                displayName = profile.displayName,
                avatarUrl = profile.avatarUrl,
                contested = contested
            )) {
                is SdkWriteResult.Broadcast -> {
                    _shieldedInviteLink.value = result.value.linkData
                    _shieldedInviteShareLink.value = result.value.shareLink
                    SdkWriteResult.Broadcast(result.value.linkData)
                }
                is SdkWriteResult.NotBroadcast -> result
                is SdkWriteResult.Ambiguous -> result
            }
        }

    val invitationPreviewImageFile by lazy {
        try {
            val storageDir: File = walletApplication.getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!
            File(storageDir, Constants.Files.INVITATION_PREVIEW_IMAGE_FILENAME)
        } catch (ex: IOException) {
            log.error(ex.message, ex)
            null
        }
    }

    fun saveInviteBitmap(invitationBitmapTemplate: View) {
        invitationPreviewImageFile?.apply {
            try {
                val bitmapFromView = bitmapFromView(invitationBitmapTemplate)
                bitmapFromView.compress(Bitmap.CompressFormat.WEBP, 100, FileOutputStream(this))
                bitmapFromView.recycle()
            } catch (ex: IOException) {
                log.error("unable to save invitation preview bitmap", ex)
            }
        }
    }

    private fun bitmapFromView(view: View): Bitmap {
        val bitmap = Bitmap.createBitmap(view.measuredWidth, view.measuredHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.layout(view.left, view.top, view.right, view.bottom)
        view.draw(canvas)
        return bitmap
    }

    fun saveTag(tag: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _invitation.value?.let {
                invitationDao.insert(it.copy(memo = tag))
            }
        }
    }

    fun logEvent(event: String) {
        analytics.logEvent(event, mapOf())
    }

    val identityId = MutableStateFlow<String?>(null)

    val invitedUserProfile: Flow<DashPayProfile?>
        get() = dashPayProfileDao.observeByUserId(identityId.value!!)

    fun updateInvitedUserProfile() {
        viewModelScope.launch(Dispatchers.IO) {
            val data = dashPayProfileDao.loadByUserId(identityId.value!!)
            if (data == null) {
                platformRepo.updateDashPayProfile(identityId.value!!)
            }
        }
    }

    private val _invitation = MutableStateFlow<Invitation?>(null)
    val invitation: StateFlow<Invitation?>
        get() = _invitation

    init {
        identityId
            .filterNotNull()
            .flatMapLatest(invitationDao::observeByUserId)
            .onEach { invitation ->
                _invitation.value = invitation
            }.launchIn(workerScope)
    }

    suspend fun getInvitedUserProfile(): DashPayProfile? = dashPayProfileDao.loadByUserId(identityId.value!!)

    override fun onCleared() {
        super.onCleared()
        workerJob.cancel()
    }
}
