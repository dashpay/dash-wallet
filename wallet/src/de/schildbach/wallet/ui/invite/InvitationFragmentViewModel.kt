/*
 * Copyright 2020 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.schildbach.wallet.ui.invite

import android.app.Application
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
import de.schildbach.wallet.data.DashPayProfile
import de.schildbach.wallet.data.Invitation
import de.schildbach.wallet.ui.dashpay.BaseProfileViewModel
import de.schildbach.wallet.ui.dashpay.work.SendInviteOperation
import de.schildbach.wallet.ui.dashpay.work.SendInviteStatusLiveData
import de.schildbach.wallet.ui.security.SecurityGuard
import kotlinx.coroutines.Dispatchers
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
    application: Application,
    private val analytics: AnalyticsService
) : BaseProfileViewModel(application) {
    private val log = LoggerFactory.getLogger(InvitationFragmentViewModel::class.java)

    private val pubkeyHash = walletApplication.wallet.currentAuthenticationKey(AuthenticationKeyChain.KeyChainType.INVITATION_FUNDING).pubKeyHash

    private val inviteId = Address.fromPubKeyHash(walletApplication.wallet.params, pubkeyHash).toBase58()

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
            val storageDir: File = application.getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!
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
        get() = AppDatabase.getAppDatabase().dashPayProfileDaoAsync().loadByUserIdDistinct(identityIdLiveData.value!!)

    fun updateInvitedUserProfile() {
        viewModelScope.launch(Dispatchers.IO) {
            val data = AppDatabase.getAppDatabase().dashPayProfileDao().loadByUserId(identityIdLiveData.value!!)
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
        val tx = walletApplication.wallet.getTransaction(invitation.txid)
        val cftx = walletApplication.wallet.getCreditFundingTransaction(tx)

        val wallet = WalletApplication.getInstance().wallet!!
        val password = SecurityGuard().retrievePassword()
        var encryptionKey: KeyParameter? = null
        try {
            encryptionKey = wallet.keyCrypter!!.deriveKey(password)
        } catch (ex: KeyCrypterException) {
            analytics.logError(ex, "create invitation link: failed to derive encryption key")
            emit("")
        }

        val invite = platformRepo.getBlockchainIdentity()!!.getInvitationString(cftx, encryptionKey)
        emit(invite)
    }
}