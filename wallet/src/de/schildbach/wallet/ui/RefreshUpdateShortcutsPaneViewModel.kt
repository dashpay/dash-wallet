package de.schildbach.wallet.ui

import androidx.lifecycle.ViewModel

class RefreshUpdateShortcutsPaneViewModel : ViewModel() {
    val onTransactionsUpdated = SingleLiveEventExt<Void>()
}
