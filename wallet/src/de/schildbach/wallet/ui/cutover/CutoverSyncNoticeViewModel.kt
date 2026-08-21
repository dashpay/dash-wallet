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
package de.schildbach.wallet.ui.cutover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.service.platform.sdk.L1ShadowSyncService
import de.schildbach.wallet.service.platform.sdk.shadowSyncPercent
import de.schildbach.wallet.service.sdkL1ScanCaughtUp
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import javax.inject.Inject

/** Everything [CutoverSyncNoticeDialogFragment] renders. */
data class CutoverSyncNoticeUIState(
    /** SDK L1 scan progress, 0..100 — 0 also means "not started yet". */
    val syncPercent: Int = 0,
    /** The scan has caught up; sending is available again. */
    val synced: Boolean = false
)

/**
 * The one-time post-UPGRADE sync explainer.
 *
 * On this build every wallet — upgrades included — becomes SDK-primary on
 * its first launch, and the SDK then runs a from-scratch compact-filter
 * scan. Until that finishes the balance is held at its last-known value and
 * sends are blocked, both of which are alarming without an explanation. This
 * sheet is that explanation, shown exactly once (the persisted
 * [DashPayConfig.CUTOVER_UPGRADE_NOTICE_PENDING] marker, armed only on the
 * upgrade seam) and carrying LIVE scan progress so the wait is legible.
 */
@HiltViewModel
class CutoverSyncNoticeViewModel @Inject constructor(
    private val dashPayConfig: DashPayConfig,
    /**
     * APPLICATION-scoped: the acknowledgment write must survive this
     * ViewModel being cleared the instant the sheet is dismissed, or the
     * marker would sometimes stay set and the sheet would come back.
     */
    private val appScope: CoroutineScope,
    l1ShadowSyncService: L1ShadowSyncService
) : ViewModel() {

    private val _uiState = MutableStateFlow(CutoverSyncNoticeUIState())
    val uiState: StateFlow<CutoverSyncNoticeUIState> = _uiState.asStateFlow()

    init {
        l1ShadowSyncService.progress
            .onEach { progress ->
                _uiState.value = CutoverSyncNoticeUIState(
                    syncPercent = shadowSyncPercent(progress),
                    // THE predicate the home header's blinking "Syncing
                    // balance" label uses (L1SyncStatusService.sdkScanCaughtUp)
                    // — no longer a hand-copied expression, so the block-
                    // pipeline drain gate applies here too.
                    synced = sdkL1ScanCaughtUp(progress)
                )
            }
            .catch { e -> log.error("cutover sync-notice progress feed failed", e) }
            .launchIn(viewModelScope)
    }

    /**
     * The user acknowledged the explainer — clear the marker so it never
     * shows again. Idempotent; never throws.
     */
    fun acknowledge() {
        appScope.launch {
            runCatching { dashPayConfig.set(DashPayConfig.CUTOVER_UPGRADE_NOTICE_PENDING, false) }
                .onFailure { log.warn("failed to clear the upgrade sync-explainer marker", it) }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(CutoverSyncNoticeViewModel::class.java)
    }
}
