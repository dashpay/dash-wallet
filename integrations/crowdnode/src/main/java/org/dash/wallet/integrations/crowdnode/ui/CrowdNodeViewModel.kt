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

package org.dash.wallet.integrations.crowdnode.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.uri.BitcoinURI
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.data.Status
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.SystemActionsService
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.ui.BalanceUIState
import org.dash.wallet.integrations.crowdnode.api.CrowdNodeApi
import org.dash.wallet.integrations.crowdnode.model.FeeInfo
import org.dash.wallet.integrations.crowdnode.model.MessageStatusException
import org.dash.wallet.integrations.crowdnode.model.OnlineAccountStatus
import org.dash.wallet.integrations.crowdnode.model.SignUpStatus
import org.dash.wallet.integrations.crowdnode.model.WithdrawalLimitPeriod
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConfig
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConstants
import java.io.IOException
import javax.inject.Inject

enum class NavigationRequest {
    BackupPassphrase, RestoreWallet, BuyDash, SendReport
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CrowdNodeViewModel @Inject constructor(
    private val globalConfig: Configuration,
    private val config: CrowdNodeConfig,
    private val walletDataProvider: WalletDataProvider,
    private val crowdNodeApi: CrowdNodeApi,
    private val clipboardManager: ClipboardManager,
    exchangeRatesProvider: ExchangeRatesProvider,
    val analytics: AnalyticsService,
    private val blockchainStateProvider: BlockchainStateProvider,
    private val systemActions: SystemActionsService,
    walletUIConfig: WalletUIConfig
) : ViewModel() {
    companion object {
        const val URL_ARG = "url"
        const val EMAIL_ARG = "email"
    }

    private var emailForAccount = ""

    val navigationCallback = SingleLiveEvent<NavigationRequest>()
    val networkError = SingleLiveEvent<Unit>()
    val onlineAccountRequest = SingleLiveEvent<Map<String, String>>()

    private val _accountAddress = MutableLiveData<Address>()
    val accountAddress: LiveData<Address>
        get() = _accountAddress

    val primaryDashAddress
        get() = crowdNodeApi.primaryAddress

    val needPassphraseBackUp
        get() = globalConfig.remindBackupSeed

    val termsAccepted = MutableLiveData(false)

    private val _hasEnoughBalance = MutableLiveData<Boolean>()
    val hasEnoughBalance: LiveData<Boolean>
        get() = _hasEnoughBalance

    private val _dashBalance = MutableLiveData<Coin>()
    val dashBalance: LiveData<Coin>
        get() = _dashBalance

    val signUpStatus: SignUpStatus
        get() = crowdNodeApi.signUpStatus.value

    val onlineAccountStatus: OnlineAccountStatus
        get() = crowdNodeApi.onlineAccountStatus.value

    val crowdNodeError: Exception?
        get() = crowdNodeApi.apiError.value

    private val _exchangeRate: MutableLiveData<ExchangeRate> = MutableLiveData()
    val exchangeRate: LiveData<ExchangeRate>
        get() = _exchangeRate

    private val _crowdNodeBalance: MutableLiveData<BalanceUIState> = MutableLiveData(BalanceUIState())
    val crowdNodeBalance: LiveData<BalanceUIState>
        get() = _crowdNodeBalance

    private var crowdNodeFee: Double = FeeInfo.DEFAULT_FEE
    val dashFormat: MonetaryFormat
        get() = globalConfig.format.noCode()

    val networkParameters: NetworkParameters
        get() = walletDataProvider.networkParameters

    val shouldShowFirstDepositBanner: Boolean
        get() = !crowdNodeApi.hasAnyDeposits() &&
            (crowdNodeBalance.value?.balance?.isLessThan(CrowdNodeConstants.MINIMUM_DASH_DEPOSIT) ?: true)

    init {
        walletDataProvider.observeSpendableBalance()
            .distinctUntilChanged()
            .onEach {
                _dashBalance.postValue(it)
                _hasEnoughBalance.postValue(it >= CrowdNodeConstants.MINIMUM_REQUIRED_DASH)
            }
            .launchIn(viewModelScope)

        walletUIConfig.observe(WalletUIConfig.SELECTED_CURRENCY)
            .filterNotNull()
            .flatMapLatest(exchangeRatesProvider::observeExchangeRate)
            .onEach(_exchangeRate::postValue)
            .launchIn(viewModelScope)

        crowdNodeApi.balance
            .onEach {
                when (it.status) {
                    Status.LOADING -> {
                        _crowdNodeBalance.postValue(
                            _crowdNodeBalance.value?.copy(balance = it.data ?: Coin.ZERO, isUpdating = true)
                        )
                    }
                    Status.SUCCESS -> {
                        _crowdNodeBalance.postValue(
                            _crowdNodeBalance.value?.copy(balance = it.data ?: Coin.ZERO, isUpdating = false)
                        )
                    }
                    Status.ERROR -> {
                        _crowdNodeBalance.postValue(_crowdNodeBalance.value?.copy(isUpdating = false))
                        networkError.call()
                    }
                    else -> _crowdNodeBalance.postValue(_crowdNodeBalance.value?.copy(isUpdating = false))
                }
            }
            .launchIn(viewModelScope)

        config.observe(CrowdNodeConfig.FEE_PERCENTAGE)
            .onEach {
                crowdNodeFee = it ?: FeeInfo.DEFAULT_FEE
            }
            .launchIn(viewModelScope)
    }

    fun backupPassphrase() {
        navigationCallback.postValue(NavigationRequest.BackupPassphrase)
    }

    fun restoreWallet() {
        navigationCallback.postValue(NavigationRequest.RestoreWallet)
    }

    fun buyDash() {
        navigationCallback.postValue(NavigationRequest.BuyDash)
    }

    fun sendReport() {
        navigationCallback.postValue(NavigationRequest.SendReport)
    }

    suspend fun recheckState() {
        crowdNodeApi.restoreStatus()
        _accountAddress.value = getOrCreateAccountAddress()
        crowdNodeApi.refreshBalance()
    }

    fun refreshBalance() {
        crowdNodeApi.refreshBalance()
    }

    fun signUp() {
        crowdNodeApi.persistentSignUp(_accountAddress.value!!)
    }

    fun linkOnlineAccount() {
        val address = _accountAddress.value!!
        val apiLinkUrl = CrowdNodeConstants.getApiLinkUrl(address)
        crowdNodeApi.trackLinkingAccount(address)
        onlineAccountRequest.postValue(
            mapOf(
                URL_ARG to apiLinkUrl
            )
        )
    }

    fun cancelLinkingOnlineAccount() {
        crowdNodeApi.stopTrackingLinked()
    }

    fun resetSignUp() {
        viewModelScope.launch {
            resetAddressAndApi()
        }
    }

    fun clearError() {
        if (crowdNodeApi.apiError.value is MessageStatusException) {
            viewModelScope.launch {
                config.set(CrowdNodeConfig.SIGNED_EMAIL_MESSAGE_ID, -1)
            }
        }

        crowdNodeApi.apiError.value = null
    }

    fun retrySignup() {
        viewModelScope.launch {
            resetAddressAndApi()
            signUp()
        }
    }

    fun resetAddress() {
        viewModelScope.launch {
            resetAddressAndApi()
        }
    }

    fun changeNotifyWhenDone(toNotify: Boolean) {
        crowdNodeApi.showNotificationOnResult = toNotify
    }

    fun setNotificationIntent(intent: Intent?) {
        crowdNodeApi.notificationIntent = intent
    }

    suspend fun getIsInfoShown(): Boolean {
        return config.get(CrowdNodeConfig.INFO_SHOWN) ?: false
    }

    fun setInfoShown(isShown: Boolean) {
        viewModelScope.launch {
            config.set(CrowdNodeConfig.INFO_SHOWN, isShown)
        }
    }

    suspend fun getShouldShowConfirmationDialog(): Boolean {
        return crowdNodeApi.onlineAccountStatus.value == OnlineAccountStatus.Confirming &&
            !(config.get(CrowdNodeConfig.CONFIRMATION_DIALOG_SHOWN) ?: false)
    }

    fun setConfirmationDialogShown(isShown: Boolean) {
        viewModelScope.launch {
            config.set(CrowdNodeConfig.CONFIRMATION_DIALOG_SHOWN, isShown)
        }
    }

    suspend fun getShouldShowOnlineInfo(): Boolean {
        return signUpStatus != SignUpStatus.LinkedOnline &&
            !(config.get(CrowdNodeConfig.ONLINE_INFO_SHOWN) ?: false)
    }

    fun setOnlineInfoShown(isShown: Boolean) {
        viewModelScope.launch {
            config.set(CrowdNodeConfig.ONLINE_INFO_SHOWN, isShown)
        }
    }

    suspend fun deposit(value: Coin, checkBalanceConditions: Boolean): Boolean {
        val emptyWallet = value >= dashBalance.value
        return crowdNodeApi.deposit(value, emptyWallet, checkBalanceConditions)
    }

    suspend fun withdraw(value: Coin): Boolean {
        return crowdNodeApi.withdraw(value)
    }

    fun copyPrimaryAddress() {
        clipboardManager.setPrimaryClip(
            ClipData.newPlainText(
                "primary dash address",
                primaryDashAddress.toString()
            )
        )
    }

    fun copyAccountAddress() {
        clipboardManager.setPrimaryClip(
            ClipData.newPlainText(
                "dash address",
                accountAddress.value.toString()
            )
        )
    }

    fun observeSignUpStatus(): LiveData<SignUpStatus> {
        return crowdNodeApi.signUpStatus.asLiveData()
    }

    fun observeOnlineAccountStatus(): LiveData<OnlineAccountStatus> {
        return crowdNodeApi.onlineAccountStatus.asLiveData()
    }

    fun observeCrowdNodeError(): LiveData<Exception?> {
        return crowdNodeApi.apiError.asLiveData()
    }

    fun signAndSendEmail(email: String) {
        viewModelScope.launch {
            emailForAccount = email

            try {
                crowdNodeApi.registerEmailForAccount(email)
            } catch (ex: IOException) {
                networkError.postCall()
            }
        }
    }

    fun initiateOnlineSignUp() {
        val signupUrl = CrowdNodeConstants.getProfileUrl(networkParameters)
        onlineAccountRequest.postValue(
            mapOf(
                URL_ARG to signupUrl,
                EMAIL_ARG to emailForAccount
            )
        )
    }

    fun getAccountUrl(): String {
        return CrowdNodeConstants.getFundsOpenUrl(
            if (signUpStatus == SignUpStatus.LinkedOnline) {
                primaryDashAddress!!
            } else {
                _accountAddress.value!!
            }
        )
    }

    fun finishSignUpToOnlineAccount() {
        crowdNodeApi.setOnlineAccountCreated()
    }

    suspend fun shouldShowWithdrawalLimitsInfo(): Boolean {
        val isShown = config.get(CrowdNodeConfig.WITHDRAWAL_LIMITS_SHOWN) ?: false
        return !crowdNodeApi.hasAnyDeposits() && !isShown
    }

    fun triggerWithdrawalLimitsShown() {
        viewModelScope.launch {
            config.set(CrowdNodeConfig.WITHDRAWAL_LIMITS_SHOWN, true)
        }
    }

    suspend fun getWithdrawalLimits(): List<Coin> {
        return listOf(
            crowdNodeApi.getWithdrawalLimit(WithdrawalLimitPeriod.PerTransaction),
            crowdNodeApi.getWithdrawalLimit(WithdrawalLimitPeriod.PerHour),
            crowdNodeApi.getWithdrawalLimit(WithdrawalLimitPeriod.PerDay)
        )
    }

    fun shareConfirmationPaymentUrl() {
        val accountAddress = accountAddress.value ?: return
        val amount = CrowdNodeConstants.API_CONFIRMATION_DASH_AMOUNT

        val paymentRequestUri = BitcoinURI.convertToBitcoinURI(accountAddress, amount, "", "")
        systemActions.shareText(paymentRequestUri)
    }

    fun logEvent(eventName: String) {
        analytics.logEvent(eventName, mapOf())
    }

    private fun getOrCreateAccountAddress(): Address {
        return crowdNodeApi.accountAddress ?: createNewAccountAddress()
    }

    private fun createNewAccountAddress(): Address {
        val address = walletDataProvider.freshReceiveAddress()
        globalConfig.crowdNodeAccountAddress = address.toBase58()

        return address
    }

    private suspend fun resetAddressAndApi() {
        _accountAddress.value = createNewAccountAddress()
        crowdNodeApi.reset()
    }

    fun getMasternodeAPY(): Double {
        val apy = blockchainStateProvider.getMasternodeAPY()
        return if (apy != 0.0) {
            apy
        } else {
            blockchainStateProvider.getLastMasternodeAPY()
        }
    }

    fun getCrowdNodeAPY(): Double {
        val withoutFees = (100.0 - crowdNodeFee) / 100
        return withoutFees * getMasternodeAPY()
    }
}
