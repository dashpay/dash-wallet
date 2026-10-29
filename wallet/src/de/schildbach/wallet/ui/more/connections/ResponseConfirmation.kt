/* Copyright 2026 Dash Core Group. SPDX-License-Identifier: GPL-3.0-or-later */
package de.schildbach.wallet.ui.more.connections

import kotlinx.coroutines.CancellationException

internal suspend fun <T> awaitResponseConfirmation(
    read: suspend () -> List<T>,
    exact: (T) -> Boolean,
    report: (Int, String) -> Unit = { _, _ -> }
) {
    var attempt = 0
    awaitGrantConfirmation(read = {
        attempt++
        try {
            read()
        } catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            report(attempt, "query failed")
            throw ex
        }
    }, confirmed = { documents ->
        val matches = documents.all(exact)
        report(attempt, when {
            !matches -> "conflicting response"
            documents.isEmpty() -> "response absent"
            else -> "exact response confirmed"
        })
        check(matches) { "Conflicting response exists for this QR" }
        documents.isNotEmpty()
    }, failureMessage = "Response not confirmed. Retry to recover.")
}
