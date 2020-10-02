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
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.DashPayProfile
import de.schildbach.wallet.ui.dashpay.work.UpdateProfileOperation

class EditProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val platformRepo = PlatformRepo.getInstance()
    private val walletApplication = application as WalletApplication

    val dashPayProfileData = AppDatabase.getAppDatabase()
            .dashPayProfileDaoAsync()
            .loadByUserIdDistinct(platformRepo.getBlockchainIdentity()!!.uniqueIdString)

    val dashPayProfile: DashPayProfile?
            get() = dashPayProfileData.value

    val updateProfileRequestState = UpdateProfileOperation.allOperationsStatus(application)

    fun broadcastUpdateProfile(dashPayProfile: DashPayProfile) {
        UpdateProfileOperation(walletApplication)
                .create(dashPayProfile)
                .enqueue()
    }
}