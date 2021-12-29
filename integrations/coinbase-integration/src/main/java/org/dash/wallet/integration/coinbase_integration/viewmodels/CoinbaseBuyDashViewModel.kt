package org.dash.wallet.integration.coinbase_integration.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.integration.coinbase_integration.model.CoinbasePaymentMethod
import org.dash.wallet.integration.coinbase_integration.model.PlaceBuyOrderParams
import org.dash.wallet.integration.coinbase_integration.model.ReviewBuyOrderModel
import org.dash.wallet.integration.coinbase_integration.repository.CoinBaseRepository
import javax.inject.Inject

@HiltViewModel
class CoinbaseBuyDashViewModel @Inject constructor(
    application: Application,
    private val coinBaseRepository: CoinBaseRepository,
    private val exchangeRatesProvider: ExchangeRatesProvider,
    val config: Configuration
) : AndroidViewModel(application) {
    fun onContinueClicked(coin: Coin, fiat: Fiat,paymentMethod : CoinbasePaymentMethod) {
//        viewModelScope.launch {
//
//            when (val response = coinBaseRepository.placeBuyOrder(PlaceBuyOrderParams())) {
//
//            }
//        }
//      ReviewBuyOrderModel(
//         "",config.coinbaseUserAccountId,paymentMethod,
//
//        val purchaseAmount:String,
//        val coinBaseFeeAmount:String,
//        val totalAmount:String,
//        val dashAmount:String
//      )
    }

}