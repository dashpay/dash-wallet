package de.schildbach.wallet.ui.dashpay

import android.text.format.DateUtils
import androidx.lifecycle.LiveData
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.NotificationItem
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.data.UsernameSortOrderBy
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.livedata.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.*

class FrequentContactsLiveData(val walletApplication: WalletApplication, private val platformRepo: PlatformRepo) : LiveData<Resource<List<UsernameSearchResult>>>(), OnContactsUpdated {

    companion object {
        const val TIMESPAN: Long = DateUtils.DAY_IN_MILLIS * 90 // 90 days
    }

    private var listening = false

    override fun onActive() {
        maybeAddEventListener()
        onContactsUpdated()
    }

    override fun onInactive() {
        maybeRemoveEventListener()
    }

    private fun maybeAddEventListener() {
        if (!listening && hasActiveObservers()) {
            platformRepo.addContactsUpdatedListener(this)
            listening = true
        }
    }

    private fun maybeRemoveEventListener() {
        if (listening) {
            platformRepo.removeContactsUpdatedListener(this)
            listening = false
        }
    }

    override fun onContactsUpdated() {
        getFrequentContacts()
    }

    fun getFrequentContacts() {
        GlobalScope.launch(Dispatchers.IO) {
            val results = arrayListOf<UsernameSearchResult>()
            val contactScores = hashMapOf<String, Int>()
            val contactRequests = platformRepo.searchContacts("", UsernameSortOrderBy.DATE_ADDED)
            when (contactRequests.status) {
                Status.SUCCESS -> {
                    val blockchainIdentity = platformRepo.getBlockchainIdentity() ?: return@launch
                    val threeMonthsAgo = Date().time - TIMESPAN
                    val contactNames = arrayListOf<String>()

                    contactRequests.data!!.forEach {
                        val transactions = blockchainIdentity.getContactTransactions(it.fromContactRequest!!.userId)
                        var count = 0

                        for (tx in transactions) {
                            if (tx.updateTime.time > threeMonthsAgo)
                                count++
                        }
                        contactScores[it.username] = count
                        contactNames.add(it.username)
                    }

                    // determine users with top 4 scores
                    contactNames.sortByDescending { contactScores[it] }
                    for (i in 0 until 4) {
                        val username = contactNames[i]
                        results.add(contactRequests.data.find { it.username == username }!!)
                    }

                    postValue(Resource.success(results))
                }
                else -> postValue(contactRequests)
            }
        }
    }
}