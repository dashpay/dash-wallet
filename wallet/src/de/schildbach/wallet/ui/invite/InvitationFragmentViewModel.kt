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
import androidx.core.os.bundleOf
import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.BlockchainIdentityDataDao
import de.schildbach.wallet.data.DashPayProfile
import de.schildbach.wallet.data.DashPayProfileDao
import de.schildbach.wallet.data.Invitation
import de.schildbach.wallet.ui.dashpay.BaseProfileViewModel
import de.schildbach.wallet.ui.dashpay.work.SendInviteOperation
import de.schildbach.wallet.ui.dashpay.work.SendInviteStatusLiveData
import de.schildbach.wallet.security.SecurityGuard
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.bitcoinj.core.Address
import org.bitcoinj.crypto.KeyCrypterException
import org.bitcoinj.wallet.AuthenticationKeyChain
import org.bouncycastle.crypto.params.KeyParameter
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
open class InvitationFragmentViewModel @Inject constructor(
    private val walletApplication: WalletApplication,
    private val analytics: AnalyticsService,
    private val platformRepo: PlatformRepo,
    blockchainIdentityDataDao: BlockchainIdentityDataDao,
    dashPayProfileDao: DashPayProfileDao
) : BaseProfileViewModel(blockchainIdentityDataDao, dashPayProfileDao) {
    private val log = LoggerFactory.getLogger(InvitationFragmentViewModel::class.java)

    private val pubkeyHash = walletApplication.wallet!!.currentAuthenticationKey(AuthenticationKeyChain.KeyChainType.INVITATION_FUNDING).pubKeyHash

    private val inviteId = Address.fromPubKeyHash(walletApplication.wallet!!.params, pubkeyHash).toBase58()

    val sendInviteStatusLiveData = SendInviteStatusLiveData(walletApplication, inviteId)

    val dynamicLinkData
        get() = sendInviteStatusLiveData.value!!.data!!.dynamicLink

    val shortDynamicLinkData
        get() = sendInviteStatusLiveData.value!!.data!!.shortDynamicLink

    fun sendInviteTransaction(): String {
        SendInviteOperation(walletApplication)
                .create(inviteId)
                .enqueue()
        return inviteId
    }

    val invitationDao = AppDatabase.getAppDatabase().invitationsDao()

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
        invitation.memo = tag
        viewModelScope.launch {
            invitationDao.insert(invitation)
        }
    }

    fun logEvent(event: String) {
        analytics.logEvent(event, bundleOf())
    }

    val identityIdLiveData = MutableLiveData<String>()

    val invitedUserProfile: LiveData<DashPayProfile?>
        get() = dashPayProfileDao.observeByUserId(identityIdLiveData.value!!).distinctUntilChanged().asLiveData()

    fun updateInvitedUserProfile() {
        viewModelScope.launch(Dispatchers.IO) {
            val data = dashPayProfileDao.loadByUserId(identityIdLiveData.value!!)
            if (data == null) {
                platformRepo.updateDashPayProfile(identityIdLiveData.value!!)
            }
        }
    }

    val invitationLiveData = Transformations.switchMap(identityIdLiveData) {
        liveData(Dispatchers.IO) {
            emit(invitationDao.loadByUserId(it)!!)
        }
    }

    val invitation: Invitation
        get() = invitationLiveData.value!!

    val invitationLinkData = liveData(Dispatchers.IO) {
        val tx = walletApplication.wallet!!.getTransaction(invitation.txid)
        val cftx = walletApplication.wallet!!.getCreditFundingTransaction(tx)

        val wallet = WalletApplication.getInstance().wallet!!
        val password = SecurityGuard().retrievePassword()
        var encryptionKey: KeyParameter? = null
        try {
            encryptionKey = wallet.keyCrypter!!.deriveKey(password)
        } catch (ex: KeyCrypterException) {
            analytics.logError(ex, "create invitation link: failed to derive encryption key")
            emit("")
        }

        val invite = platformRepo.blockchainIdentity.getInvitationString(cftx, encryptionKey)
        emit(invite)
    }

    suspend fun getInvitedUserProfile(): DashPayProfile? =
        dashPayProfileDao.loadByUserId(identityIdLiveData.value!!)
}
