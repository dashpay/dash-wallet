/* Copyright 2026 Dash Core Group. SPDX-License-Identifier: GPL-3.0-or-later */
package de.schildbach.wallet.ui.more.connections

import org.dashj.platform.dpp.identity.Identity
import org.dashj.platform.dpp.identity.IdentityPublicKey
import de.schildbach.wallet.ui.more.connections.AppConnectKeyState.sameKey

// PV14 permits at most ten disables and six public keys in creation per transition.
internal suspend fun updateIdentityInBatches(
    additions: List<IdentityPublicKey>,
    disableIds: List<Int>,
    read: suspend () -> Identity,
    submit: suspend (Identity, List<IdentityPublicKey>, List<Int>) -> Unit
): Identity {
    val adds = additions.chunked(6)
    val disables = disableIds.distinct().chunked(10)
    var identity = read()
    repeat(maxOf(adds.size, disables.size)) { index ->
        if (index > 0) identity = read()
        val add = adds.getOrElse(index) { emptyList() }.filter { expected ->
            val actual = identity.publicKeys.firstOrNull { it.id == expected.id }
            check(actual == null || sameKey(actual, expected)) { "Pending key ID is occupied. Retry revocation to recover." }
            actual == null
        }
        val disable = disables.getOrElse(index) { emptyList() }.filter { id ->
            checkNotNull(identity.publicKeys.firstOrNull { it.id == id }) { "Revocation key is missing" }.disabledAt == null
        }
        if (add.isNotEmpty() || disable.isNotEmpty()) {
            submit(identity, add, disable)
            identity = awaitGrantConfirmation(read, confirmed = { current ->
                disable.all { id -> current.publicKeys.any { it.id == id && it.disabledAt != null } } &&
                    add.all { expected -> current.publicKeys.any { sameKey(it, expected) } }
            }, failureMessage = "Identity update batch not confirmed. Grant records were retained; retry to recover.")
        }
    }
    return identity
}
