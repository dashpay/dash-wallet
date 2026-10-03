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

package de.schildbach.wallet.ui.payments

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Context
import de.schildbach.wallet.data.WalletData
import org.dash.wallet.common.services.ReceiveAddressUnavailableException
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.slf4j.LoggerFactory
import javax.inject.Inject

private val log = LoggerFactory.getLogger(PaymentsViewModel::class.java)

/**
 * What the Receive screen is able to advertise, owned by the ViewModel rather
 * than by the page's view so that it outlives both a tab switch and a view
 * recreation.
 */
sealed interface ReceiveAddressState {
    /** Nothing asked for yet. */
    data object Idle : ReceiveAddressState

    /**
     * A read is in flight. The live read BLOCKS for up to
     * `CutoverUiDataService.BINDING_WAIT_MS` (5s) waiting for the engine to
     * bind, so this state is both long-lived and the single-flight latch.
     */
    data object Loading : ReceiveAddressState

    /** The engine answered; [address] is safe to advertise. */
    data class Available(val address: String) : ReceiveAddressState

    /**
     * The read failed. Retryable — the usual cause is an engine that has not
     * bound yet. [attempt] numbers the failed reads so two consecutive
     * failures are distinct values: [kotlinx.coroutines.flow.StateFlow]
     * conflates equal ones, and a collector that was not scheduled in between
     * would otherwise never see the second failure.
     */
    data class Unavailable(val attempt: Int) : ReceiveAddressState
}

@ExperimentalCoroutinesApi
@HiltViewModel
class PaymentsViewModel @Inject constructor(
    platformRepo: PlatformRepo,
    identityConfig: BlockchainIdentityConfig,
    private val walletDataProvider: WalletData,
    private val analytics: AnalyticsService
): ViewModel() {
    var fromQuickReceive: Boolean = false

    private val _dashPayProfile = MutableStateFlow<DashPayProfile?>(null)
    val dashPayProfile = _dashPayProfile.asLiveData()

    /**
     * The address the Receive screen advertises. Deliberately the `Live`
     * accessor: post-cutover the dashj key chain is HELD, so its "current"
     * pointer is frozen wherever the restore left it — index 0 on a fresh
     * restore — and the plain read handed the payer back the very address the
     * wallet had already been funded on (SR-03 / D-003). The live read asks the
     * SDK engine, whose pointer comes from the SPV scan's used-set and so skips
     * the used range. Already on [Dispatchers.IO], which the live read requires.
     */
    suspend fun getCurrentAddress() = withContext(Dispatchers.IO) {
        walletDataProvider.wallet?.let {
            Context.propagate(it.context)
            walletDataProvider.currentReceiveAddressLive()
        } ?: error("Wallet not yet initialised")
    }

    /** Same live-read reasoning as [getCurrentAddress]. */
    suspend fun getFreshAddress() = withContext(Dispatchers.IO) {
        walletDataProvider.wallet?.let {
            Context.propagate(it.context)
            walletDataProvider.freshReceiveAddressLive()
        } ?: error("Wallet not yet initialised")
    }

    private val _receiveAddress = MutableStateFlow<ReceiveAddressState>(ReceiveAddressState.Idle)
    val receiveAddress = _receiveAddress.asStateFlow()

    /** Written only by the one in-flight read the gate below admits. */
    private var failedReceiveReads = 0

    /**
     * Ask for the address the Receive screen should advertise, unless one is
     * already in hand or already on its way.
     *
     * Called both when the page's view is created (a prefetch that may run
     * while the page is offscreen) and every time the page becomes RESUMED, so
     * that a read which failed offscreen — the engine had not bound yet, and
     * ViewPager2 keeps the failed page around rather than recreating it — is
     * retried when the user actually selects Receive.
     *
     * The gate is the state itself, flipped with an atomic
     * [getAndUpdate]: whoever observes a startable state is the one caller that
     * proceeds. That matters because the read BLOCKS for up to 5s waiting for
     * the engine to bind, so repeated resumes land squarely inside a read that
     * is still running. It also deliberately runs on [viewModelScope] rather
     * than the view's scope: pausing the page must not cancel a read in
     * flight, both because the blocking wait would keep its IO thread anyway
     * and because the next resume would then start a second, overlapping one.
     */
    fun requestReceiveAddress() {
        val previous = _receiveAddress.getAndUpdate { current ->
            if (canStartReceiveAddressRequest(current)) ReceiveAddressState.Loading else current
        }

        if (!canStartReceiveAddressRequest(previous)) {
            return
        }

        viewModelScope.launch {
            _receiveAddress.value = try {
                // get current address is much faster, because the wallet doesn't need to be saved
                ReceiveAddressState.Available(getCurrentAddress().toBase58())
            } catch (ex: ReceiveAddressUnavailableException) {
                // Post-cutover the engine could not answer, and there is no safe
                // substitute — the held dashj chain's pointer is frozen on an
                // address the wallet has already been paid on (SR-03). Advertise
                // nothing and stay retryable: the failure is usually just an
                // engine that has not bound yet.
                log.warn("receive address unavailable; advertising nothing", ex)
                ReceiveAddressState.Unavailable(++failedReceiveReads)
            }
        }
    }

    init {
        identityConfig.observe()
            .flatMapLatest { identity ->
                val userId = identity.userId
                if (userId != null && identity.hasUsername) {
                    platformRepo.observeProfileByUserId(userId)
                } else {
                    emptyFlow()
                }
            }
            .onEach {
                _dashPayProfile.value = it
            }
            .launchIn(viewModelScope)
    }

    fun logSpecifyAmount() {
        if (fromQuickReceive) {
            analytics.logEvent(AnalyticsConstants.LockScreen.QUICK_RECEIVE_AMOUNT, mapOf())
        } else {
            analytics.logEvent(AnalyticsConstants.SendReceive.SPECIFY_AMOUNT, mapOf())
        }
    }

    fun logEvent(eventName: String) {
        analytics.logEvent(eventName, mapOf())
    }

    companion object {
        /**
         * Whether a receive-address request may START from [current].
         *
         * [ReceiveAddressState.Loading] is refused because the read is already
         * on its way — a second one would block on the same engine read and
         * could hand back a different address. [ReceiveAddressState.Available]
         * is refused because the address is already shown: re-reading it on
         * every tab switch churns the engine and risks advertising a different
         * address each time the user flips tabs. Only a never-asked
         * ([ReceiveAddressState.Idle]) or FAILED
         * ([ReceiveAddressState.Unavailable]) state starts a read, which is
         * exactly what makes becoming RESUMED after an offscreen failure a
         * retry rather than a no-op.
         */
        @VisibleForTesting
        @JvmStatic
        fun canStartReceiveAddressRequest(current: ReceiveAddressState): Boolean =
            current is ReceiveAddressState.Idle || current is ReceiveAddressState.Unavailable
    }
}
