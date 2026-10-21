/* Copyright 2026 Dash Core Group. SPDX-License-Identifier: GPL-3.0-or-later */
package de.schildbach.wallet.ui.more.connections

import org.dashj.platform.dpp.identity.IdentityPublicKey
import org.dashj.platform.sdk.Purpose

internal object AppConnectKeyState {
    enum class Presence { MISSING, CONFLICTING, LIVE, INACTIVE }
    data class KeyState(val id: Int, val authentication: Boolean, val expectedLive: Boolean, val presence: Presence)

    fun live(key: IdentityPublicKey, time: Long) =
        key.disabledAt == null && (key.expiresAt == null || key.expiresAt!! > time)

    // Limits may change through IdentityKeyLimitsUpdate without changing key ownership.
    fun sameKeyIdentity(a: IdentityPublicKey, b: IdentityPublicKey, compareId: Boolean = true) =
        (!compareId || a.id == b.id) && a.type == b.type && a.purpose == b.purpose &&
            a.securityLevel == b.securityLevel && a.data.contentEquals(b.data) && a.readOnly == b.readOnly &&
            a.contractBounds?.toJSON() == b.contractBounds?.toJSON()

    fun sameKey(a: IdentityPublicKey, b: IdentityPublicKey, compareId: Boolean = true) =
        sameKeyIdentity(a, b, compareId) && a.totalBudget == b.totalBudget && a.expiresAt == b.expiresAt

    fun inspect(expected: List<IdentityPublicKey>, actual: List<IdentityPublicKey>, time: Long) = expected.map { key ->
        val current = actual.firstOrNull { it.id == key.id }
        KeyState(key.id, key.purpose == Purpose.AUTHENTICATION, live(key, time), when {
            current == null -> Presence.MISSING
            !sameKeyIdentity(current, key) -> Presence.CONFLICTING
            live(current, time) -> Presence.LIVE
            else -> Presence.INACTIVE
        })
    }

    fun unresolved(keys: List<KeyState>): Boolean {
        val auth = keys.filter { it.authentication }
        // Registration is atomic and IDs cannot be reused. One collision prevents the whole addition.
        if (auth.any { it.presence == Presence.CONFLICTING }) return false
        return auth.any { it.expectedLive && it.presence == Presence.MISSING }
    }

    fun revocableIds(keys: List<KeyState>) =
        keys.filter { it.authentication && it.presence == Presence.LIVE }.map { it.id }.distinct()
}
