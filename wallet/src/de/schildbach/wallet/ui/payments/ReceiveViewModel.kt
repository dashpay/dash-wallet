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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.dash.wallet.common.money.Coin
import org.dash.wallet.common.payments.parsers.AddressNetwork
import org.dash.wallet.common.payments.parsers.PaymentURI
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.util.readRetryDelayMs
import org.dash.wallet.common.util.retryReadErrors
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
 * @property loading [selectedTab] has no read result on show yet: true until its first read
 *   completes, so a fresh page shows progress rather than "unavailable". A re-read leaves the
 *   last result up until it answers: [address] keeps the value being revalidated (a resume
 *   does not blank the QR), and after a failed read this stays false (still [unavailable]).
 * @property username the user's username, or null when they have none.
 */
data class ReceiveUIState(
    val tabs: List<ReceiveBalanceTab> = listOf(ReceiveBalanceTab.CORE),
    val selectedTab: ReceiveBalanceTab = ReceiveBalanceTab.CORE,
    val address: String? = null,
    val loading: Boolean = true,
    val username: String? = null
) {
    /**
     * The QR content for [address]: a `dash:` URI on Core, the bare address or ID elsewhere.
     * Built on first read and cached for this instance, not in the constructor, so the many
     * `copy`s that only change other fields never build the URI unless someone reads it. Not
     * part of equals/hashCode: it is derived from [selectedTab], [address] and [username].
     */
    val qrPayload: String? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        address?.let { ReceiveViewModel.qrPayloadOrNull(selectedTab, it, username) }
    }

    /** The last read for [selectedTab] could not produce an address. */
    val unavailable: Boolean
        get() = !loading && address == null

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
    private val supportsPlatform: Boolean,
    retryDelayMs: (Long) -> Long = ::readRetryDelayMs
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

        /**
         * The identity, or null while it cannot be read: [BlockchainIdentityConfig.observe]
         * turns a failed read into "no identity" and then completes, so one transient failure
         * would hide the Identity tab for good. This keeps the read alive with
         * [retryReadErrors] and emits null (no identity, no username) while it fails.
         */
        internal fun identityFlow(
            identityConfig: BlockchainIdentityConfig,
            retryDelayMs: (Long) -> Long = ::readRetryDelayMs
        ): Flow<BlockchainIdentityData?> = identityConfig.observePreservingErrors()
            .map<BlockchainIdentityData, BlockchainIdentityData?> { it }
            .retryReadErrors(null, retryDelayMs) { e, attempt ->
                // never the identity itself: only that the read failed
                log.warn("identity read failed (attempt {}); withholding the Identity tab", attempt, e)
            }
            // repeated failures each emit null: one is enough
            .distinctUntilChanged()

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
         * tabs are not base58 L1 addresses ([AddressNetwork.fromDashAddress] rejects them), so
         * they encode the bare value.
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

        /** The wallet's network, for its `dash:` URI scheme: from its id, never from an address. */
        private val walletNetwork: AddressNetwork by lazy { AddressNetwork.fromId(Constants.NETWORK_PARAMETERS.id) }

        /**
         * The Specify amount request for a tab other than Core: exactly
         * `dash:<address>?amount=<amount>`, built by [PaymentURI.convertToPaymentURI] as Core's
         * request is. That builder appends [address] as given, so it carries bech32
         * shielded/Platform addresses and identity IDs too; the network comes from the wallet,
         * not from [AddressNetwork.fromDashAddress], which accepts only base58 L1 addresses.
         */
        fun amountRequestPayload(address: String, amount: Coin): String {
            require(address.isNotEmpty()) { "no address for the payment request" }
            require(amount.signum() >= 0) { "the requested amount must not be negative" }
            return PaymentURI.convertToPaymentURI(walletNetwork, address, amount, null, null)
        }

        /** [qrPayloadFor], or null (fail closed) when the URI builder rejects [address]. */
        internal fun qrPayloadOrNull(tab: ReceiveBalanceTab, address: String, username: String?): String? =
            try {
                qrPayloadFor(tab, address, username)
            } catch (e: IllegalArgumentException) {
                log.warn("cannot build the receive QR payload for {}", tab, e)
                null
            }
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
        // A failed read hides the Shield and Platform tabs and is retried, like the Internal tab.
        shieldedEnabledFlow(
            shieldedFlag = dashPayConfig.observePreservingErrors(DashPayConfig.USE_KOTLIN_SDK_SHIELDED),
            onError = { e, attempt -> log.warn("shielded flag read failed (attempt {})", attempt, e) }
        )
            .onEach {
                shieldedEnabled = it
                applyTabs()
            }
            .launchIn(viewModelScope)

        // Same wiring as PaymentsViewModel's profile observer, but fail closed and retried: a
        // failed identity read withholds the Identity tab and the username (null below) until a
        // later read succeeds, instead of reading as "no identity" for the rest of this screen.
        identityFlow(identityConfig, retryDelayMs)
            .onEach { data ->
                val previousId = identity?.userId
                identity = data
                applyTabs()
                if (_uiState.value.selectedTab == ReceiveBalanceTab.IDENTITY && previousId != data?.userId) {
                    load(ReceiveBalanceTab.IDENTITY, force = true)
                }
            }
            .flatMapLatest { data ->
                val userId = data?.userId
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
        _uiState.update { it.copy(selectedTab = tab, address = null, loading = true) }
        load(tab, force = true)
    }

    fun logEvent(eventName: String) {
        analytics.logEvent(eventName, mapOf())
    }

    private fun hasUsername(): Boolean = identity?.hasUsername == true

    private fun publishUsername() {
        val username = if (hasUsername()) profileUsername ?: identity?.activeUsername else null
        _uiState.update { it.copy(username = username) }
    }

    private fun applyTabs() {
        val tabs = tabsFor(supportsPlatform, shieldedEnabled, hasUsername())
        // The selected balance went away (flag off, username gone): back to Core.
        val lostSelection = _uiState.value.selectedTab !in tabs
        _uiState.update {
            if (lostSelection) {
                it.copy(tabs = tabs, selectedTab = ReceiveBalanceTab.CORE, address = null, loading = true)
            } else {
                it.copy(tabs = tabs)
            }
        }
        publishUsername()
        if (lostSelection) {
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
        // Keep what is on screen while revalidating it — the address, or the "unavailable" of
        // the last failed read — until this read replaces it; a failure below takes it down.
        _uiState.update { if (it.unavailable) it else it.copy(loading = true) }
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
                    // Fail closed: an address the URI builder rejects is not advertised. The
                    // check reads the published state's own (cached) payload: built once.
                    val next = state.copy(address = address, loading = false)
                    if (address != null && next.qrPayload == null) next.copy(address = null) else next
                }
            }
        }
    }
}

/**
 * The shielded flag as a never-failing flow: a failed read counts as off (as
 * [DashPayConfig.get] reading absent does for [PaymentsFragment]), is reported to
 * [onError] and is retried, so the flow never completes on an [Exception] (see
 * [retryReadErrors]: a cancellation or an [Error] still propagates). Used by the Receive
 * balance tabs.
 */
internal fun shieldedEnabledFlow(
    shieldedFlag: Flow<Boolean?>,
    onError: (Throwable, Long) -> Unit = { _, _ -> },
    retryDelayMs: (Long) -> Long = ::readRetryDelayMs
): Flow<Boolean> = shieldedFlag
    .map { it == true }
    .retryReadErrors(false, retryDelayMs, onError)
    .distinctUntilChanged()
