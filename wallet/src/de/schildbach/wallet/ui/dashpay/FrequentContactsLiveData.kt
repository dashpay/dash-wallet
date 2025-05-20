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

import android.text.format.DateUtils
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.data.UsernameSortOrderBy
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.service.platform.PlatformSyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.dash.wallet.common.WalletDataProvider
import org.dashj.platform.dashpay.BlockchainIdentity
import org.slf4j.LoggerFactory
import java.util.*

class FrequentContactsLiveData(
    private val walletData: WalletDataProvider,
    val platformRepo: PlatformRepo,
    platformSyncService: PlatformSyncService,
    val scope: CoroutineScope
) : ContactsBasedLiveData<Resource<List<UsernameSearchResult>>>(platformSyncService) {

    companion object {
        const val TIMESPAN: Long = DateUtils.DAY_IN_MILLIS * 90 // 90 days
        const val TOP_CONTACT_COUNT = 4
        private val log = LoggerFactory.getLogger(FrequentContactsLiveData::class.java)
    }

    override fun onContactsUpdated() {
        getFrequentContacts()
    }

    fun getFrequentContacts() {
        scope.launch(Dispatchers.IO) {
            val contactRequests = platformRepo.searchContacts("", UsernameSortOrderBy.DATE_ADDED)
            when (contactRequests.status) {
                Status.SUCCESS -> {
                    if (!platformRepo.hasBlockchainIdentity) {
                        return@launch
                    }

                    val blockchainIdentity = platformRepo.blockchainIdentity
                    val threeMonthsAgo = Date().time - TIMESPAN

                    val results = getTopContacts(contactRequests.data!!, listOf(), blockchainIdentity, threeMonthsAgo, true)

                    if (results.size < TOP_CONTACT_COUNT) {
                        val moreResults = getTopContacts(contactRequests.data, results, blockchainIdentity, threeMonthsAgo, false)
                        results.addAll(moreResults)
                    }

                    postValue(Resource.success(results))
                }
                else -> postValue(contactRequests)
            }
        }
    }

    private fun getTopContacts(items: List<UsernameSearchResult>,
                               ignore: List<UsernameSearchResult>,
                               blockchainIdentity: BlockchainIdentity,
                               threeMonthsAgo: Long,
                               sent: Boolean
    ): ArrayList<UsernameSearchResult> {
        val results = arrayListOf<UsernameSearchResult>()
        val contactScores = hashMapOf<String, Int>()
        val contactIds = arrayListOf<String>()
        // only include fully established contacts
        val contacts = items.filter { it.requestSent && it.requestReceived }

        contacts.forEach {
            val transactions = blockchainIdentity.getContactTransactions(it.fromContactRequest!!.userIdentifier, it.fromContactRequest!!.accountReference)
            var count = 0

            for (tx in transactions) {
                val txValue = tx.getValue(walletData.transactionBag)
                if ((sent && txValue.isNegative) || (!sent && txValue.isPositive)) {
                    if (tx.updateTime.time > threeMonthsAgo) {
                        count++
                    }
                }
            }
            contactScores[it.fromContactRequest!!.userId] = count
            contactIds.add(it.fromContactRequest!!.userId)
        }

        // determine users with top TOP_CONTACT_COUNT non-zero scores
        // if ignore has some items, then find TOP_CONTACT_COUNT - ignore.size
        contactIds.sortByDescending { contactScores[it] }
        var count = 0
        for (id in contactIds) {
            if (contactScores[id] != 0 && ignore.find { it.fromContactRequest!!.userId == id } == null) {
                results.add(items.find { it.fromContactRequest!!.userId == id }!!)
                count++
                if (count == TOP_CONTACT_COUNT - ignore.size)
                    break
            }
        }
        return results
    }
}