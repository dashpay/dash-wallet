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
package de.schildbach.wallet.ui.dashpay

import androidx.annotation.StringRes
import de.schildbach.wallet_test.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.dashj.platform.dpp.errors.concensus.basic.identity.InvalidInstantAssetLockProofException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two KNOWN transient identity-registration failure shapes (live
 * incident, testnet: an asset-lock-funded username creation looped for
 * ~10 minutes with only a generic "processing" tile):
 *
 * - the funding tx confirmed but Platform's consensus core height still
 *   trails it — dashj retries per new block until Platform catches up;
 * - the funding tx has no InstantSend lock yet — dashj rejects the
 *   instant-lock proof and falls back to the (slower) chain-lock proof.
 */
enum class RetryStatusHint {
    /** "Asset Lock proof core chain height N is higher than the current consensus core height M". */
    CORE_HEIGHT_LAG,

    /** No IS lock on the funding tx yet ("instantLock == null" / invalid instant lock proof). */
    WAITING_FOR_ISLOCK
}

/**
 * Classifies a registration failure into a [RetryStatusHint], or null
 * for anything unrecognized (an unknown error must not change the hint —
 * the generic processing/error UI already covers it). Walks the cause
 * chain: the known messages sometimes arrive wrapped.
 */
internal fun identityRetryStatusHint(t: Throwable): RetryStatusHint? {
    var current: Throwable? = t
    var depth = 0
    while (current != null && depth < 5) {
        val message = current.message ?: ""
        when {
            message.contains("is higher than the current consensus core height") ->
                return RetryStatusHint.CORE_HEIGHT_LAG
            current is InvalidInstantAssetLockProofException ||
                message.contains("Invalid instant lock proof") ||
                message.contains("instantLock == null") ->
                return RetryStatusHint.WAITING_FOR_ISLOCK
        }
        current = current.cause
        depth++
    }
    return null
}

/**
 * The user-facing string for a [RetryStatusHint], or null when there is
 * no hint to show. Kept as a pure `@StringRes` lookup (no Context) so both
 * the home-screen processing tile
 * ([de.schildbach.wallet.ui.main.WalletTransactionsFragment]) and the
 * username-flow processing dialog
 * ([de.schildbach.wallet.ui.username.request.UsernameSubmitStatusDialogs])
 * render the SAME copy, and so the mapping can be unit-tested without an
 * Android runtime.
 */
@StringRes
internal fun retryStatusHintTextRes(hint: RetryStatusHint?): Int? = when (hint) {
    RetryStatusHint.CORE_HEIGHT_LAG -> R.string.identity_processing_network_catching_up
    RetryStatusHint.WAITING_FOR_ISLOCK -> R.string.identity_processing_waiting_confirmation
    null -> null
}

/**
 * In-memory channel for the transient identity-registration status hint,
 * deliberately NOT persisted (no Room schema change): the hint is only
 * meaningful while [CreateIdentityService] is actively retrying, and a
 * process death restarts the state machine anyway.
 *
 * Written by the registration path ([PlatformRepo.registerIdentity] /
 * [CreateIdentityService]); observed by the home-screen identity
 * processing tile ([HistoryHeaderAdapter] via
 * [de.schildbach.wallet.ui.main.WalletTransactionsFragment]).
 */
@Singleton
class IdentityCreationStatusHolder @Inject constructor() {
    private val _statusHint = MutableStateFlow<RetryStatusHint?>(null)
    val statusHint: StateFlow<RetryStatusHint?> = _statusHint.asStateFlow()

    fun setHint(hint: RetryStatusHint?) {
        _statusHint.value = hint
    }

    fun clear() {
        _statusHint.value = null
    }
}
