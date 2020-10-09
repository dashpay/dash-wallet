package de.schildbach.wallet.ui.dashpay

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.DashPayProfile
import de.schildbach.wallet.data.UsernameSortOrderBy
import kotlinx.coroutines.launch
import org.bitcoinj.core.Context
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionConfidence
import java.util.*

class TransactionsViewModel(application: Application) : AndroidViewModel(application) {

    enum class Direction {
        RECEIVED, SENT
    }

    val direction = MutableLiveData<Direction?>()
    val transactionsLiveData = MediatorLiveData<Pair<List<Transaction>,
            Map<Sha256Hash, DashPayProfile>>>()
    private val balanceLiveData = WalletBalanceLiveData()

    init {
        load()
        transactionsLiveData.addSource(direction) {
            load()
        }
        transactionsLiveData.addSource(balanceLiveData) {
            load()
        }
    }

    fun load() {
        viewModelScope.launch {
            val wallet = WalletApplication.getInstance().wallet
            Context.propagate(Constants.CONTEXT)

            val contactsTransactions: HashMap<Sha256Hash, DashPayProfile> = hashMapOf()
            val contactsByIdentity: HashMap<String, DashPayProfile> = hashMapOf()
            val platformRepo = PlatformRepo.getInstance()
            val userIdentity = platformRepo.getBlockchainIdentity()
            if (userIdentity != null) {
                val contacts = PlatformRepo.getInstance().searchContacts("",
                        UsernameSortOrderBy.LAST_ACTIVITY, false)
                contacts.data?.forEach {
                    contactsByIdentity[it.dashPayProfile.userId] = it.dashPayProfile
                }
            }

            val transactions = wallet.getTransactions(true)
            val filteredTransactions = arrayListOf<Transaction>()
            transactions.filterTo(filteredTransactions, {
                val sent = it.getValue(wallet).signum() < 0
                val isInternal = it.purpose == Transaction.Purpose.KEY_ROTATION
                direction.value == Direction.RECEIVED && !sent
                        && !isInternal || direction.value == null ||
                        direction.value == Direction.SENT && sent && !isInternal
            })

            filteredTransactions.sortWith(object : Comparator<Transaction> {
                override fun compare(tx1: Transaction?, tx2: Transaction?): Int {
                    val pending1 = tx1!!.confidence.confidenceType == TransactionConfidence.ConfidenceType.PENDING
                    val pending2 = tx2!!.confidence.confidenceType == TransactionConfidence.ConfidenceType.PENDING
                    if (pending1 != pending2) return if (pending1) -1 else 1
                    val updateTime1 = tx1.updateTime
                    val time1 = updateTime1?.time ?: 0
                    val updateTime2 = tx2.updateTime
                    val time2 = updateTime2?.time ?: 0
                    return if (time1 != time2) if (time1 > time2) -1 else 1 else tx1.hash.compareTo(tx2.hash)
                }
            })

            filteredTransactions.forEach {
                val contactId = userIdentity?.getContactForTransaction(it)
                if (contactId != null) {
                    val contactProfile = contactsByIdentity[contactId]
                    if (contactProfile != null) {
                        contactsTransactions[it.txId] = contactProfile
                    }
                }
            }

            transactionsLiveData.postValue(Pair(filteredTransactions, contactsTransactions))
        }
    }

}