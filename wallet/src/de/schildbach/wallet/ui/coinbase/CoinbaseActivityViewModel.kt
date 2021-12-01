package de.schildbach.wallet.ui.coinbase

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import org.dash.wallet.integration.coinbase_integration.repository.CoinBaseRepository
import javax.inject.Inject

@HiltViewModel
class CoinbaseActivityViewModel @Inject constructor(
    application: Application,
    private val coinBaseRepository: CoinBaseRepository
) : AndroidViewModel(application)
