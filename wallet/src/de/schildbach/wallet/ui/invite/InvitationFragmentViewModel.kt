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
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject

@ExperimentalCoroutinesApi
@HiltViewModel
open class InvitationFragmentViewModel @Inject constructor(
    private val walletApplication: WalletApplication,
    private val analytics: AnalyticsService,
    private val platformRepo: PlatformRepo,
    private val invitationDao: InvitationsDao,
    private val identityRepository: IdentityRepository,
    private val sdkShieldedInviteCreation: SdkShieldedInviteCreation,
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
     * Fund a SHIELDED (L2) invitation directly from the shielded pool — the
     * private-invitation counterpart of [sendInviteTransaction]. [contested]
     * (derived from the fee the inviter picked) selects the exit denomination.
     * On success the funded link is published to [shieldedInviteLink]. Runs the
     * ~30s proof off the main thread.
     */
    suspend fun createShieldedInvite(contested: Boolean): SdkWriteResult<InvitationLinkData> =
        withContext(Dispatchers.IO) {
            val profile = identityRepository.getLocalUserProfile()
                ?: return@withContext SdkWriteResult.NotBroadcast("no local user profile")
            val result = sdkShieldedInviteCreation.createShieldedInvite(
                username = profile.username,
                displayName = profile.displayName,
                avatarUrl = profile.avatarUrl,
                contested = contested
            )
            if (result is SdkWriteResult.Broadcast) {
                _shieldedInviteLink.value = result.value
            }
            result
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
