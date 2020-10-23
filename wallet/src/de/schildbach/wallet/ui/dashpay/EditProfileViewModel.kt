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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.DashPayProfile
import de.schildbach.wallet.ui.dashpay.work.UpdateProfileOperation
import de.schildbach.wallet.ui.dashpay.work.UpdateProfileStatusLiveData

class EditProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val walletApplication = application as WalletApplication

    // blockchainIdentityData is observed instead of using PlatformRepo.getBlockchainIdentity()
    // since neither PlatformRepo nor blockchainIdentity is initialized when there is no username
    private val blockchainIdentityData = AppDatabase.getAppDatabase()
            .blockchainIdentityDataDaoAsync().loadBase()

    val dashPayProfileData = blockchainIdentityData.switchMap {
        if (it != null) {
            AppDatabase.getAppDatabase().dashPayProfileDaoAsync().loadByUserIdDistinct(it.userId!!)
        } else {
            MutableLiveData()   //empty
        }
    }
    val dashPayProfile
        get() = dashPayProfileData.value!!

    val updateProfileRequestState = UpdateProfileStatusLiveData(application)

    fun broadcastUpdateProfile(displayName: String, publicMessage: String) {
        val dashPayProfile = dashPayProfileData.value!!
        val updatedProfile = DashPayProfile(dashPayProfile.userId, dashPayProfile.username,
                displayName, publicMessage, dashPayProfile.avatarUrl,
                dashPayProfile.createdAt, dashPayProfile.updatedAt)
        UpdateProfileOperation(walletApplication)
                .create(updatedProfile)
                .enqueue()
    }
}