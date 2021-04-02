package de.schildbach.wallet.ui.invite

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import de.schildbach.wallet.ui.SingleLiveEvent

class InvitesHistoryFilterViewModel(application: Application) : AndroidViewModel(application) {
    val filterBy = SingleLiveEvent<InvitesHistoryViewModel.Filter>()
}