/* Copyright 2026 Dash Core Group. SPDX-License-Identifier: GPL-3.0-or-later */
package de.schildbach.wallet.ui.more.connections

import android.content.Context
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Locking cancels the visible dialog, not the pending result or callback. */
internal suspend fun awaitUnlockedConnectionDialog(
    activity: FragmentActivity,
    owner: LifecycleOwner,
    locked: StateFlow<Boolean>,
    isUnlocked: () -> Boolean,
    create: () -> AdaptiveDialog
): Boolean? = coroutineScope {
    val result = CompletableDeferred<Boolean?>()
    val presentation = launch {
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            locked.collectLatest { isLocked ->
                if (!isLocked && isUnlocked() &&
                    activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                    !activity.supportFragmentManager.isStateSaved) {
                    val confirmed = create().showGuardedAsync(activity, isUnlocked)
                    if (isUnlocked()) result.complete(confirmed)
                }
            }
        }
    }
    try { result.await() } finally { presentation.cancel() }
}

internal fun connectionCallbackDialog(context: Context, host: String): AdaptiveDialog = AdaptiveDialog.create(
    null,
    context.getString(R.string.dash_connect_return_title, host),
    context.getString(R.string.dash_connect_return_warning),
    context.getString(android.R.string.cancel),
    context.getString(R.string.dash_connect_return_open)
)
