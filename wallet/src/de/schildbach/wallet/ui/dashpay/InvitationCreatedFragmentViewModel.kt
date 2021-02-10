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
package de.schildbach.wallet.ui.dashpay

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Environment
import android.view.View
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import androidx.lifecycle.liveData
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.Constants
import de.schildbach.wallet.data.Invitation
import de.schildbach.wallet.ui.dashpay.work.SendInviteStatusLiveData
import kotlinx.coroutines.Dispatchers
import org.bitcoinj.core.Address
import org.bitcoinj.wallet.AuthenticationKeyChain
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

open class InvitationCreatedFragmentViewModel(application: Application) : BaseProfileViewModel(application) {

    private val log = LoggerFactory.getLogger(InvitationCreatedFragmentViewModel::class.java)

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
        AppDatabase.getAppDatabase().invitationsDaoAsync().insert(invitation)
    }

    private val pubkeyHash = walletApplication.wallet.currentAuthenticationKey(AuthenticationKeyChain.KeyChainType.INVITATION_FUNDING).pubKeyHash
    val inviteId = Address.fromPubKeyHash(walletApplication.wallet.params, pubkeyHash).toBase58()

    val identityIdLiveData = MutableLiveData<String>()

    val invitationLiveData = Transformations.switchMap(identityIdLiveData) {
        liveData (Dispatchers.IO) {
            emit(AppDatabase.getAppDatabase().invitationsDao().loadByUserId(it)!!)
        }
    }

    val invitation : Invitation
        get() = invitationLiveData.value!!

    fun getInvitationLink(): String {
        val tx = walletApplication.wallet.getTransaction(invitation.txid)
        val cftx = walletApplication.wallet.getCreditFundingTransaction(tx)
        val invite = platformRepo.getBlockchainIdentity()!!.getInvitationString(cftx)

        return "sample://dashpay.invitation/$invite}"
    }

    val sendInviteStatusLiveData = SendInviteStatusLiveData(walletApplication, inviteId)

}