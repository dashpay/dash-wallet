/*
 * Copyright 2022 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui.main

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.SharedPreferences
import android.util.Log
import androidx.core.os.bundleOf
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.data.BlockchainStateDao
import de.schildbach.wallet.ui.SingleLiveEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.ExchangeRate
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.transactions.TransactionWrapper
import org.dash.wallet.integrations.crowdnode.transactions.FullCrowdNodeSignUpTxSet
import javax.inject.Inject
import kotlin.time.ExperimentalTime
import kotlin.time.Duration.Companion.seconds

@HiltViewModel
@FlowPreview
@ExperimentalTime
@ExperimentalCoroutinesApi
class MainViewModel @Inject constructor(
    private val analytics: AnalyticsService,
    private val clipboardManager: ClipboardManager,
    private val config: Configuration,
    blockchainStateDao: BlockchainStateDao,
    exchangeRatesProvider: ExchangeRatesProvider,
    walletDataProvider: WalletDataProvider
) : ViewModel() {
    companion object {
        private val THROTTLE_DURATION = 1.seconds
    }

    private val listener: SharedPreferences.OnSharedPreferenceChangeListener
    private val currencyCode = MutableStateFlow(config.exchangeCurrencyCode)

    val balanceDashFormat: MonetaryFormat = config.format.noCode()
    val onTransactionsUpdated = SingleLiveEvent<Unit>()

    private val _transactions = MutableLiveData<Collection<TransactionWrapper>>()
    val transactions: MutableLiveData<Collection<TransactionWrapper>>
        get() = _transactions

    private val _isBlockchainSynced = MutableLiveData<Boolean>()
    val isBlockchainSynced: LiveData<Boolean>
        get() = _isBlockchainSynced

    private val _isBlockchainSyncFailed = MutableLiveData<Boolean>()
    val isBlockchainSyncFailed: LiveData<Boolean>
        get() = _isBlockchainSyncFailed

    private val _exchangeRate = MutableLiveData<ExchangeRate>()
    val exchangeRate: LiveData<ExchangeRate>
        get() = _exchangeRate

    private val _balance = MutableLiveData<Coin>()
    val balance: LiveData<Coin>
        get() = _balance

    private val _hideBalance = MutableLiveData<Boolean>()
    val hideBalance: LiveData<Boolean>
        get() = _hideBalance

    init {
        _hideBalance.value = config.hideBalance
        _transactions.value = walletDataProvider.wrapAllTransactions(
            FullCrowdNodeSignUpTxSet(walletDataProvider.networkParameters)
        )
        walletDataProvider.observeTransactions()
            .debounce(THROTTLE_DURATION)
            .filterNotNull()
            .onEach {
                Log.i("CROWDNODE", "observeTransactions")
                _transactions.postValue(walletDataProvider.wrapAllTransactions(
                    FullCrowdNodeSignUpTxSet(walletDataProvider.networkParameters)
                ))
            }
            .launchIn(viewModelScope)

        blockchainStateDao.observeState()
            .filterNotNull()
            .onEach {
                _isBlockchainSynced.postValue(it.isSynced())
                _isBlockchainSyncFailed.postValue(it.syncFailed())
            }
            .launchIn(viewModelScope)

        walletDataProvider.observeBalance()
            .onEach(_balance::postValue)
            .launchIn(viewModelScope)

        currencyCode.filterNotNull()
            .flatMapLatest { code ->
                exchangeRatesProvider.observeExchangeRate(code)
                    .filterNotNull()
            }
            .onEach(_exchangeRate::postValue)
            .launchIn(viewModelScope)

        listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Configuration.PREFS_KEY_EXCHANGE_CURRENCY) {
                currencyCode.value = config.exchangeCurrencyCode
            }
        }
        config.registerOnSharedPreferenceChangeListener(listener)
    }

    fun logEvent(event: String) {
        analytics.logEvent(event, bundleOf())
    }

    fun logError(ex: Exception, details: String) {
        analytics.logError(ex, details)
    }

    fun getClipboardInput(): String {
        var input: String? = null

        if (clipboardManager.hasPrimaryClip()) {
            val clip = clipboardManager.primaryClip ?: return ""
            val clipDescription = clip.description

            if (clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST)) {
                input = clip.getItemAt(0).uri?.toString()
            } else if (clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)
                || clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
            ) {
                input = clip.getItemAt(0).text?.toString()
            }
        }

        return input ?: ""
    }

    fun triggerHideBalance() {
        val pastValue = _hideBalance.value ?: config.hideBalance
        _hideBalance.value = !pastValue

        if (_hideBalance.value == true) {
            logEvent(AnalyticsConstants.Home.HIDE_BALANCE)
        } else {
            logEvent(AnalyticsConstants.Home.SHOW_BALANCE)
        }
    }

    override fun onCleared() {
        super.onCleared()
        config.unregisterOnSharedPreferenceChangeListener(listener)
    }
}