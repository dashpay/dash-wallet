/* Copyright 2026 Dash Core Group. SPDX-License-Identifier: GPL-3.0-or-later */
package de.schildbach.wallet.ui.more.connections

import android.content.Context
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import de.schildbach.wallet.ui.LockScreenActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.dash.wallet.common.ui.components.ButtonData
import org.dash.wallet.common.ui.getActivity
import org.dash.wallet.common.ui.components.LocalDashColors
import org.dash.wallet.common.ui.components.ModalDialog
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.Style

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
            combine(locked, activity.lifecycle.resumedFlow()) { isLocked, hostResumed -> !isLocked && hostResumed }
                .distinctUntilChanged()
                .collectLatest { ready ->
                    if (ready && isUnlocked() && !activity.supportFragmentManager.isStateSaved) {
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

internal fun identityMismatchMessage(
    context: Context,
    mismatch: ConnectionsViewModel.ScanOutcome.IdentityMismatch
): String = context.getString(
    R.string.dash_connect_identity_mismatch_message,
    mismatch.requestedIdentityId,
    mismatch.walletUsername?.let { "$it (${mismatch.walletIdentityId})" } ?: mismatch.walletIdentityId
)

private fun Lifecycle.resumedFlow(): Flow<Boolean> = callbackFlow {
    val observer = LifecycleEventObserver { _, _ -> trySend(currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    addObserver(observer)
    awaitClose { removeObserver(observer) }
}.conflate().distinctUntilChanged()

/** The mismatch to show now: only while the host and the fragment view are both resumed and the wallet is unlocked. */
internal fun identityMismatchToShow(
    outcome: Flow<ConnectionsViewModel.ScanOutcome>,
    host: Lifecycle,
    view: Lifecycle,
    locked: Flow<Boolean>,
    isUnlocked: () -> Boolean
): Flow<ConnectionsViewModel.ScanOutcome.IdentityMismatch?> =
    combine(outcome, host.resumedFlow(), view.resumedFlow(), locked) { current, hostResumed, viewResumed, isLocked ->
        (current as? ConnectionsViewModel.ScanOutcome.IdentityMismatch)
            ?.takeIf { hostResumed && viewResumed && !isLocked && isUnlocked() }
    }.distinctUntilChanged()

@Composable
internal fun IdentityMismatchDialog(
    mismatch: ConnectionsViewModel.ScanOutcome.IdentityMismatch,
    onDismiss: () -> Unit
) {
    val colors = LocalDashColors.current
    val activity = LocalContext.current.getActivity()
    ModalDialog(
        showDialog = true,
        onDismissRequest = onDismiss,
        icon = ImageVector.vectorResource(R.drawable.ic_warning_yellow_circle),
        heading = stringResource(R.string.dash_connect_identity_mismatch_title),
        content = {
            // The dialog window bypasses the activity's dispatch, so selecting an id would not reset auto-lock.
            SelectionContainer(
                Modifier.pointerInput(activity) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                            activity?.onUserInteraction()
                        }
                    }
                }
            ) {
                Text(
                    text = identityMismatchMessage(LocalContext.current, mismatch),
                    style = MyTheme.Body2Regular,
                    textAlign = TextAlign.Center,
                    color = colors.textPrimary
                )
            }
        },
        buttons = listOf(
            ButtonData(label = stringResource(R.string.button_ok), onClick = onDismiss, style = Style.FilledBlue)
        )
    )
}

/** Disconnecting revokes the app's keys on Platform for good, so it is confirmed first. */
internal fun disconnectConfirmationDialog(context: Context, appName: String): AdaptiveDialog = AdaptiveDialog.create(
    R.drawable.ic_warning_yellow_circle,
    context.getString(R.string.dash_connect_disconnect_confirm_title, appName),
    context.getString(R.string.dash_connect_disconnect_confirm_message, appName),
    context.getString(android.R.string.cancel),
    context.getString(R.string.dash_connect_disconnect_action)
)

/**
 * The Disconnect action of the details sheet and of an Active list row. An older (legacy-only) connection is
 * only forgotten here, so it is removed at once; any other connection's keys are revoked on Platform for good,
 * so that is confirmed first. Returns whether [remove] or [revoke] ran.
 */
internal suspend fun disconnectConnection(
    connection: DAppConnection,
    activity: FragmentActivity,
    owner: LifecycleOwner,
    locked: StateFlow<Boolean>,
    isUnlocked: () -> Boolean,
    remove: (DAppConnection) -> Unit,
    revoke: (DAppConnection) -> Unit
): Boolean {
    if (!isUnlocked()) return false
    if (connection.legacyOnly) {
        remove(connection)
        return true
    }
    val confirmed = awaitUnlockedConnectionDialog(activity, owner, locked, isUnlocked) {
        disconnectConfirmationDialog(activity, connection.name)
    }
    if (confirmed != true || !isUnlocked()) return false
    revoke(connection)
    return true
}

/**
 * Runs [disconnectConnection] for [connection] in this fragment's view scope, so closing the fragment (the sheet
 * dismissed on lock, say) drops a confirmation still waiting. [onDisconnected] follows a remove or revoke.
 */
internal fun Fragment.launchConnectionDisconnect(
    viewModel: ConnectionsViewModel,
    connection: DAppConnection,
    onDisconnected: () -> Unit = {}
): Job? {
    if (!viewModel.isWalletUnlocked) return null
    val host = activity as? LockScreenActivity ?: return null
    val owner = viewLifecycleOwner
    return owner.lifecycleScope.launch {
        val done = disconnectConnection(connection, host, owner, host.walletLocked, { viewModel.isWalletUnlocked },
            viewModel::removeConnection, viewModel::disconnect)
        if (done) onDisconnected()
    }
}
