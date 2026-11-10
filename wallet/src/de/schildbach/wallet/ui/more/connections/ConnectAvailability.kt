/*
 * Copyright 2026 Dash Core Group.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.schildbach.wallet.ui.more.connections

import de.schildbach.wallet.Constants
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.updateAndGet

/**
 * DashConnect needs Platform protocol version 14: key funding limits and expiry, contract-group
 * bounds, remaining-budget queries and the AppConnect response contract all arrive with it. Only a
 * version the network reported counts ([PlatformRepo.readProtocolVersion]): the SDK starts at a
 * per-network version and keeps it when a lookup fails, so its number alone proves nothing.
 */
object ConnectAvailability {
    const val MIN_PROTOCOL_VERSION = 14
    const val VERSION_TOO_OLD_ERROR = "DashConnect requires Platform protocol version $MIN_PROTOCOL_VERSION"
    const val VERSION_UNKNOWN_ERROR = "Cannot check the Platform version. Check your connection and try again."

    /** The highest version the network reported in this process; 0 until one was read. */
    private val reportedVersion = MutableStateFlow(0)

    /** This build and device can run DashConnect; the network may still be too old for it. */
    val isSupported: Boolean
        get() = Constants.SUPPORTS_PLATFORM && Constants.SUPPORTS_CONNECT

    /** Whether DashConnect can be offered, as far as the versions read so far tell. */
    val availability: Flow<Boolean> = reportedVersion
        .map { isSupported && it >= MIN_PROTOCOL_VERSION }
        .distinctUntilChanged()

    /**
     * Asks the network for its protocol version unless it already reported one new enough. Null when
     * the network did not answer, so the caller can offer a retry instead of calling the network too old.
     */
    suspend fun isAvailable(platformRepo: PlatformRepo): Boolean? {
        if (!isSupported) return false
        if (reportedVersion.value >= MIN_PROTOCOL_VERSION) return true
        val version = platformRepo.readProtocolVersion()
        // An overlapping probe may have read a newer version first, even when this one failed; answer
        // with the one kept.
        val kept = reportedVersion.updateAndGet { maxOf(it, version ?: 0) }
        return if (kept >= MIN_PROTOCOL_VERSION) true else version?.let { false }
    }

    internal fun resetForTest() {
        reportedVersion.value = 0
    }
}
