/* Copyright 2026 Dash Core Group. SPDX-License-Identifier: GPL-3.0-or-later */
package de.schildbach.wallet.ui.more.connections

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Carries the initiating unlock session through IO and native signing callbacks. */
internal class ConnectionAuthorization(private val isUnlocked: () -> Boolean) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ConnectionAuthorization>

    fun check() {
        if (!isUnlocked()) throw CancellationException("Wallet locked. Unlock and review the operation again.")
    }
}

internal fun CoroutineContext.connectionAuthorization(): () -> Unit {
    val authorization = checkNotNull(this[ConnectionAuthorization]) { "Explicit unlocked approval required" }
    return {
        ensureActive()
        authorization.check()
    }.also { it() }
}
