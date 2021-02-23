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
import androidx.lifecycle.viewModelScope
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.Constants
import de.schildbach.wallet.data.Invitation
import de.schildbach.wallet.ui.dashpay.work.SendInviteOperation
import de.schildbach.wallet.ui.dashpay.work.SendInviteStatusLiveData
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.bitcoinj.core.Address
import org.bitcoinj.wallet.AuthenticationKeyChain
import org.dashevo.dpp.toHexString
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.*

open class InviteFriendViewModel(application: Application) : BaseProfileViewModel(application) {

    private val log = LoggerFactory.getLogger(InviteFriendViewModel::class.java)

    private val pubkeyHash = walletApplication.wallet.currentAuthenticationKey(AuthenticationKeyChain.KeyChainType.INVITATION_FUNDING).pubKeyHash

    private val inviteId = Address.fromPubKeyHash(walletApplication.wallet.params, pubkeyHash).toBase58()

    val sendInviteStatusLiveData = SendInviteStatusLiveData(walletApplication, inviteId)

    fun sendInviteTransaction(): String {
         SendInviteOperation(walletApplication)
                .create(inviteId)
                .enqueue()
        return inviteId
    }
}