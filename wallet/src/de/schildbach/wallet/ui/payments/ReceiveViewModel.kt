/*
 * Copyright 2026 Dash Core Group.
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

package de.schildbach.wallet.ui.payments

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.dash.wallet.common.money.Coin
import org.dash.wallet.common.payments.parsers.AddressNetwork
import org.dash.wallet.common.payments.parsers.PaymentURI
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.slf4j.LoggerFactory
import javax.inject.Inject

/** A balance the Advanced Receive page can receive into, in display order. */
enum class ReceiveBalanceTab {
    CORE,
    SHIELD,
    PLATFORM,
    IDENTITY
}

/**
 * @property tabs the balance tabs on show; Core is always first.
 * @property selectedTab the tab whose [address] is on show.
 * @property address what [selectedTab] advertises, or null when there is nothing safe to show
 *   (still loading, or unavailable — see [unavailable]).
 * @property qrPayload the QR content for [address]: a `dash:` URI on Core, the bare address or
 *   ID elsewhere.
 * @property loading a read for [selectedTab] is in flight. [address] may still hold the value
 *   being revalidated, so a resume does not blank the QR.
 * @property unavailable the last read for [selectedTab] could not produce an address.
 * @property username the user's username, or null when they have none.
 */
data class ReceiveUIState(
    val tabs: List<ReceiveBalanceTab> = listOf(ReceiveBalanceTab.CORE),
    val selectedTab: ReceiveBalanceTab = ReceiveBalanceTab.CORE,
    val address: String? = null,
    val qrPayload: String? = null,
    val loading: Boolean = false,
    val unavailable: Boolean = false,
    val username: String? = null
) {
    /**
     * Specify amount is on every tab. Core reads a fresh address itself once the amount is
     * entered (as it always has), so it is always enabled; the other tabs put [address] in
     * the request, so they need one on show.
     */
    val specifyAmountEnabled: Boolean
        get() = selectedTab == ReceiveBalanceTab.CORE || address != null

    /** A username identifies a DashPay user, who is paid on Core; the Identity tab is theirs too. */
    val showUsername: Boolean
        get() = username != null &&
            (selectedTab == ReceiveBalanceTab.CORE || selectedTab == ReceiveBalanceTab.IDENTITY)
}

/**
 * The Receive tab of the Advanced payments screen (Figma 7062:49303).
 *
 * Each balance tab reads its own [ReceiveAddressSource]; Core uses exactly the live read
 * of [PaymentsViewModel.requestReceiveAddress], fail-closed: a failed read shows no QR, never
 * a stale or dashj address. Reads are single-flight per tab, and a read whose tab is no longer
 * selected (or that was superseded) never publishes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReceiveViewModel internal constructor(
    private val sources: ReceiveAddressSources,
    platformRepo: PlatformRepo,
    identityConfig: BlockchainIdentityConfig,
    dashPayConfig: DashPayConfig,
    private val analytics: AnalyticsService,
    private val supportsPlatform: Boolean
) : ViewModel() {

    @Inject
    constructor(
        sources: ReceiveAddressSources,
        platformRepo: PlatformRepo,
        identityConfig: BlockchainIdentityConfig,
        dashPayConfig: DashPayConfig,
        analytics: AnalyticsService
    ) : this(sources, platformRepo, identityConfig, dashPayConfig, analytics, Constants.SUPPORTS_PLATFORM)

    companion object {
        private val log = LoggerFactory.getLogger(ReceiveViewModel::class.java)

        /** The balance tabs to show, in display order. */
        @VisibleForTesting
        fun tabsFor(supportsPlatform: Boolean, shieldedEnabled: Boolean, hasUsername: Boolean): List<ReceiveBalanceTab> =
            buildList {
                add(ReceiveBalanceTab.CORE)
                if (supportsPlatform && shieldedEnabled) {
                    add(ReceiveBalanceTab.SHIELD)
                    add(ReceiveBalanceTab.PLATFORM)
                }
                if (hasUsername) {
                    add(ReceiveBalanceTab.IDENTITY)
                }
            }

        /**
         * The QR content for [address] on [tab]. Core keeps today's `dash:` URI (with the
         * username, as [org.dash.wallet.common.ui.receive.ReceiveInfoView] does); the other
         * tabs are not base58 L1 addresses, which [PaymentURI] cannot carry, so they encode
         * the bare value.
         */
        @VisibleForTesting
        fun qrPayloadFor(tab: ReceiveBalanceTab, address: String, username: String?): String =
            when (tab) {
                ReceiveBalanceTab.CORE -> PaymentURI.convertToPaymentURI(
                    AddressNetwork.fromDashAddress(address),
                    address,
                    null,
                    null,
                    null,
                    username
                )
                else -> address
            }

        /**
         * The Specify amount request for a tab other than Core: exactly
         * `dash:<address>?amount=<amount>`, the amount in DASH formatted as Core's `dash:` URI
         * formats it ([Coin.toPlainString], as [PaymentURI.convertToPaymentURI] does). Built
         * by hand because [PaymentURI] and [AddressNetwork] accept only base58 L1 addresses,
         * not bech32 shielded/Platform addresses or identity IDs.
         */
        fun amountRequestPayload(address: String, amount: Coin): String {
            require(address.isNotEmpty()) { "no address for the payment request" }
            require(amount.signum() >= 0) { "the requested amount must not be negative" }
            return "${AddressNetwork.DASH_SCHEME}:$address?amount=${amount.toPlainString()}"
        }

        /**
         * The shielded flag as a never-failing flow: a failed read hides the
         * Shield and Platform tabs and is retried, like the Internal tab
         * ([advancedPaymentsTabsFlow]).
         */
        internal fun shieldedEnabledFlow(
            shieldedFlag: Flow<Boolean?>,
            retryDelayMs: (Long) -> Long = { attempt -> (attempt.coerceIn(0L, 11L) + 1) * 5_000L }
        ): Flow<Boolean> = shieldedFlag
            .map { it == true }
            .retryWhen { e, attempt ->
                log.warn("shielded flag read failed (attempt {})", attempt, e)
                emit(false)
                delay(retryDelayMs(attempt))
                true
            }
            .distinctUntilChanged()
    }

    private val _uiState = MutableStateFlow(ReceiveUIState())
    val uiState: StateFlow<ReceiveUIState> = _uiState.asStateFlow()

    private var shieldedEnabled = false
    private var identity: BlockchainIdentityData? = null
    private var profileUsername: String? = null

    /** The in-flight read, the tab it is for, and its generation: only the latest may publish. */
    private var loadJob: Job? = null
    private var loadingTab: ReceiveBalanceTab? = null
    private var loadGeneration = 0

    init {
        shieldedEnabledFlow(dashPayConfig.observePreservingErrors(DashPayConfig.USE_KOTLIN_SDK_SHIELDED))
            .onEach {
                shieldedEnabled = it
                applyTabs()
            }
            .launchIn(viewModelScope)

        // Same wiring as PaymentsViewModel's profile observer.
        identityConfig.observe()
            .onEach { data ->
                val previousId = identity?.userId
                identity = data
                applyTabs()
                if (_uiState.value.selectedTab == ReceiveBalanceTab.IDENTITY && previousId != data.userId) {
                    load(ReceiveBalanceTab.IDENTITY, force = true)
                }
            }
            .flatMapLatest { data ->
                val userId = data.userId
                if (userId != null && data.hasUsername) {
                    platformRepo.observeProfileByUserId(userId)
                } else {
                    flowOf(null)
                }
            }
            .onEach { profile ->
                profileUsername = profile?.username
                publishUsername()
            }
            .launchIn(viewModelScope)
    }

    /** Re-read the selected tab's address: on every resume, like [PaymentsReceiveFragment]. */
    fun refresh() {
        load(_uiState.value.selectedTab, force = false)
    }

    fun selectTab(tab: ReceiveBalanceTab) {
        val state = _uiState.value
        if (tab == state.selectedTab || tab !in state.tabs) return
        _uiState.update {
            it.copy(selectedTab = tab, address = null, qrPayload = null, loading = false, unavailable = false)
        }
        load(tab, force = true)
    }

    fun logEvent(eventName: String) {
        analytics.logEvent(eventName, mapOf())
    }

    private fun hasUsername(): Boolean = identity?.hasUsername == true

    private fun publishUsername() {
        val username = if (hasUsername()) profileUsername ?: identity?.activeUsername else null
        _uiState.update { state ->
            val payload = state.address?.let { qrPayloadOrNull(state.selectedTab, it, username) }
            state.copy(username = username, qrPayload = payload)
        }
    }

    private fun applyTabs() {
        val tabs = tabsFor(supportsPlatform, shieldedEnabled, hasUsername())
        val state = _uiState.value
        if (tabs == state.tabs) {
            publishUsername()
            return
        }
        if (state.selectedTab in tabs) {
            _uiState.update { it.copy(tabs = tabs) }
            publishUsername()
        } else {
            // The selected balance went away (flag off, username gone): back to Core.
            _uiState.update {
                it.copy(
                    tabs = tabs,
                    selectedTab = ReceiveBalanceTab.CORE,
                    address = null,
                    qrPayload = null,
                    loading = false,
                    unavailable = false
                )
            }
            publishUsername()
            load(ReceiveBalanceTab.CORE, force = true)
        }
    }

    private fun sourceFor(tab: ReceiveBalanceTab): ReceiveAddressSource = when (tab) {
        ReceiveBalanceTab.CORE -> sources.core
        ReceiveBalanceTab.SHIELD -> sources.shielded
        ReceiveBalanceTab.PLATFORM -> sources.platform
        ReceiveBalanceTab.IDENTITY -> ReceiveAddressSource { identity?.takeIf { it.hasUsername }?.userId }
    }

    /**
     * Start a read for [tab]. A read already in flight for the same tab is reused unless
     * [force]; any other in-flight read is superseded and will not publish.
     */
    private fun load(tab: ReceiveBalanceTab, force: Boolean) {
        if (!force && loadJob?.isActive == true && loadingTab == tab) return
        loadJob?.cancel()
        val generation = ++loadGeneration
        loadingTab = tab
        // Keep what is on screen while revalidating it; a failure below takes it down.
        _uiState.update { it.copy(loading = true) }
        loadJob = viewModelScope.launch {
            val address = try {
                sourceFor(tab).address()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("receive address read failed for {}; advertising nothing", tab, e)
                null
            }
            if (generation != loadGeneration) return@launch
            _uiState.update { state ->
                if (state.selectedTab != tab) {
                    state
                } else {
                    val payload = address?.let { qrPayloadOrNull(tab, it, state.username) }
                    val shown = address.takeIf { payload != null }
                    state.copy(
                        address = shown,
                        qrPayload = payload,
                        loading = false,
                        unavailable = shown == null
                    )
                }
            }
        }
    }

    private fun qrPayloadOrNull(tab: ReceiveBalanceTab, address: String, username: String?): String? =
        try {
            qrPayloadFor(tab, address, username)
        } catch (e: IllegalArgumentException) {
            // Fail closed: an address the URI builder rejects is not advertised.
            log.warn("cannot build the receive QR payload for {}", tab, e)
            null
        }
}
