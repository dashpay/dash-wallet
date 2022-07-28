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
package de.schildbach.wallet.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.liveData
import androidx.lifecycle.switchMap
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.DashPayProfile
import de.schildbach.wallet.data.UsernameSortOrderBy
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.TransactionsLiveData
import java.util.*

class WalletTransactionsFragmentViewModel(application: Application) : AndroidViewModel(application) {

    val platformRepo = PlatformRepo.getInstance()

//    private val transactionsData = TransactionsLiveData(application as WalletApplication)
//
//    val transactionHistoryItemData = transactionsData.switchMap {
//        liveData {
//            val userIdentity = platformRepo.getBlockchainIdentity()
//            val contactsByIdentity: HashMap<String, DashPayProfile> = hashMapOf()
//            if (userIdentity != null) {
//                val contacts = PlatformRepo.getInstance().searchContacts("",
//                        UsernameSortOrderBy.LAST_ACTIVITY, false)
//                contacts.data?.forEach {
//                    contactsByIdentity[it.dashPayProfile.userId] = it.dashPayProfile
//                }
//            }
//
//            val result = arrayListOf<TransactionsAdapter.TransactionHistoryItem>()
//            it.forEach {
//                val contactId = userIdentity?.getContactForTransaction(it)
//                if (contactId != null) {
//                    val contactProfile = contactsByIdentity[contactId]
//                    result.add(TransactionsAdapter.TransactionHistoryItem(it, contactProfile))
//                } else {
//                    result.add(TransactionsAdapter.TransactionHistoryItem(it, null))
//                }
//            }
//
//            emit(result)
//        }
//    }

    val blockchainIdentityData = AppDatabase.getAppDatabase().blockchainIdentityDataDaoAsync().loadBase()
}