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
import androidx.lifecycle.Transformations
import androidx.lifecycle.liveData
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.livedata.RegistrationResource
import de.schildbach.wallet.livedata.RegistrationStep
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.livedata.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.bitcoinj.crypto.KeyCrypter
import org.bitcoinj.wallet.DeterministicSeed
import org.bouncycastle.crypto.params.KeyParameter
import org.dashevo.dashpay.BlockchainIdentity
import org.dashevo.dpp.identity.Identity

class DashPayViewModel(application: Application) : AndroidViewModel(application) {

    private val platformRepo = PlatformRepo(application as WalletApplication)
    private val walletApplication = application as WalletApplication

    private val usernameLiveData = MutableLiveData<String>()

    private val registerUsernameLiveData = MutableLiveData<CreateUsernameInfo>()
    //private val walletAuthenticationKeysLiveData = MutableLiveData<KeyParameter>()
    //private val fundingTransactionLiveData = MutableLiveData<KeyParameter>()
    //private val identityLiveData = MutableLiveData<BlockchainIdentity>()
    //private val preorderLiveData = MutableLiveData<BlockchainIdentity>()
    //private val nameLiveData = MutableLiveData<BlockchainIdentity>()

    // Job instance (https://stackoverflow.com/questions/57723714/how-to-cancel-a-running-livedata-coroutine-block/57726583#57726583)
    private var getUsernameJob = Job()

    val getUsernameLiveData = Transformations.switchMap(usernameLiveData) { username ->
        getUsernameJob.cancel()
        getUsernameJob = Job()
        liveData(context = getUsernameJob + Dispatchers.IO) {
            emit(Resource.loading(null))
            emit(platformRepo.getUsername(username))
        }
    }

    fun searchUsername(username: String) {
        usernameLiveData.value = username
    }

    override fun onCleared() {
        super.onCleared()
        getUsernameJob.cancel()
    }

    val isPlatformAvailableLiveData = liveData(Dispatchers.IO) {
        emit(Resource.loading(null))
        emit(platformRepo.isPlatformAvailable())
    }
    // username registration functions

    val createUsernameLiveData = Transformations.switchMap(registerUsernameLiveData) { usernameInfo ->
        val wallet = walletApplication.wallet
        liveData(Dispatchers.IO) {
            //create the Blockchain Identity object (this needs to be saved somewhere eventually)
            val blockchainIdentity = BlockchainIdentity(Identity.IdentityType.USER, 0, wallet)

            // STEP 1: upgrade the wallet
            emit(RegistrationResource.loading(RegistrationStep.UPGRADING_WALLET, null))
            emit(platformRepo.addWalletAuthenticationKeys(usernameInfo.seed, usernameInfo.keyParameter))

            // STEP 2a: create funding transaction
            emit(RegistrationResource.loading(RegistrationStep.CREDIT_FUNDING_TX_CREATING, null))
            val status = platformRepo.createCreditFundingTransaction(blockchainIdentity)
            emit(status)
            if (status.status == Status.ERROR) {
                return@liveData
            }

            // STEP 2a: create funding transaction
            emit(RegistrationResource.loading(RegistrationStep.CREDIT_FUNDING_TX_SENDING, null))

            // STEP 2a: create funding transaction
            emit(RegistrationResource.loading(RegistrationStep.CREDIT_FUNDING_TX_SENT, null))

            // STEP 2a: create funding transaction
            emit(RegistrationResource.loading(RegistrationStep.CREDIT_FUNDING_TX_CREATING, null))

            // STEP 2d: determine that the credit funding transaction was accepted
            emit(RegistrationResource.loading(RegistrationStep.CREDIT_FUNDING_TX_CONFIRMED, null))

            // STEP 3a: register the identity
            emit(RegistrationResource.loading(RegistrationStep.IDENTITY_REGISTERING, null))

            // STEP 3b: check for the registered identity
            emit(RegistrationResource.loading(RegistrationStep.IDENTITY_REGISTERED, null))

            // STEP 4a: submit the preorder
            emit(RegistrationResource.loading(RegistrationStep.PREORDER_REGISTERING, null))

            // STEP 4b: check for the preorder
            emit(RegistrationResource.loading(RegistrationStep.PREORDER_REGISTERED, null))

            // STEP 4a: submit the name
            emit(RegistrationResource.loading(RegistrationStep.USERNAME_REGISTERING, null))

            // STEP 4b: check for the name
            emit(RegistrationResource.loading(RegistrationStep.USERNAME_REGISTERED, null))

        }
    }

    fun createUsername(username: String, seed: DeterministicSeed, keyParameter: KeyParameter?) {
        registerUsernameLiveData.value = CreateUsernameInfo(username, seed, keyParameter)
    }
}