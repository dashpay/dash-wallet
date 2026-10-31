/* Copyright 2026 Dash Core Group. SPDX-License-Identifier: GPL-3.0-or-later */
package de.schildbach.wallet.ui.more.connections

import kotlinx.coroutines.delay

internal suspend fun <T> awaitGrantConfirmation(
    read: suspend () -> T,
    confirmed: suspend (T) -> Boolean,
    failureMessage: String = "Grant keys are not confirmed. Response was not published. Retry to recover."
): T {
    repeat(3) { attempt ->
        if (attempt > 0) delay(2_000L * attempt)
        val state = read()
        if (confirmed(state)) return state
    }
    error(failureMessage)
}
