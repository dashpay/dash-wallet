package de.schildbach.wallet.ui.invite

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.ui.SingleLiveEvent
import org.slf4j.LoggerFactory

class InvitesHistoryViewModel(application: Application) : AndroidViewModel(application){
    companion object {
        val log = LoggerFactory.getLogger(InvitesHistoryViewModel::class.java)
    }

    private val walletApplication = application as WalletApplication

    val invitationHistory = AppDatabase.getAppDatabase().invitationsDaoAsync().loadAll()

    enum class Filter {
        ALL,
        CLAIMED,
        PENDING
    }

    val filter = Filter.ALL

    val filterClick = SingleLiveEvent<Filter>()
}