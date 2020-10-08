package de.schildbach.wallet.ui.dashpay

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import kotlinx.coroutines.launch
import org.bitcoinj.core.Context
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionConfidence
import org.bitcoinj.wallet.Wallet
import java.util.*

class TransactionsViewModel(application: Application) : AndroidViewModel(application) {

    enum class Direction {
        RECEIVED, SENT
    }

    private val wallet by lazy { WalletApplication.getInstance().wallet }

    val direction = MutableLiveData<Direction?>()
    val transactionsLiveData = MediatorLiveData<List<Transaction>>()
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

    fun load(wallet: Wallet = WalletApplication.getInstance().wallet) {
        viewModelScope.launch {
            Context.propagate(Constants.CONTEXT)

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

            transactionsLiveData.postValue(filteredTransactions)
        }
    }

}