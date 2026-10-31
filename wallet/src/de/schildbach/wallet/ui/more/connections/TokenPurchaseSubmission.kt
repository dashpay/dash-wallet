/* Copyright 2026 Dash Core Group. SPDX-License-Identifier: GPL-3.0-or-later */
package de.schildbach.wallet.ui.more.connections

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** Marks the native handoff, not the start of preflight or a potentially suspending disk write. */
internal class TokenPurchaseSubmission : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TokenPurchaseSubmission>
    @Volatile var started = false
        private set

    fun markStarted() { started = true }
}

internal suspend fun submitWithPurchaseReceipt(
    config: DashConnectConfig,
    receipt: JSONObject,
    preflight: () -> Unit,
    submit: () -> Unit
) {
    val submission = checkNotNull(coroutineContext[TokenPurchaseSubmission]) { "Purchase submission context required" }
    preflight()
    // Finish the write even if cancelled, then either hand off or durably undo the unsubmitted receipt.
    withContext(NonCancellable) {
        config.savePurchase(receipt)
        try {
            preflight()
        } catch (error: Exception) {
            config.removeUnsubmittedPurchase(receipt.getString("id"))
            throw error
        }
        submission.markStarted()
        try {
            submit()
            config.savePurchase(receipt.put("status", "submitted"))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            throw TokenPurchaseOutcomeUnknown(error)
        }
    }
}
