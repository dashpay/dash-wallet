/*
 * Copyright 2026 Dash Core Group.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.schildbach.wallet.ui.more.connections

import de.schildbach.wallet.Constants
import de.schildbach.wallet.service.platform.ContestedUsernameFees
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * DashConnect needs Platform protocol version 14: key funding limits and expiry, contract-group
 * bounds, remaining-budget queries and the AppConnect response contract all arrive with it. The
 * network's version is the one [PlatformRepo.refreshProtocolVersion] records, which only goes up.
 */
object ConnectAvailability {
    const val MIN_PROTOCOL_VERSION = 14

    /** This build and device can run DashConnect; the network may still be too old for it. */
    val isSupported: Boolean
        get() = Constants.SUPPORTS_PLATFORM && Constants.SUPPORTS_CONNECT

    /** Whether DashConnect can be offered, as far as the last known protocol version tells. */
    val availability: Flow<Boolean> = ContestedUsernameFees.protocolVersions
        .map { isSupported && it >= MIN_PROTOCOL_VERSION }
        .distinctUntilChanged()

    /** Asks the network for its protocol version unless it is already known to be new enough. */
    suspend fun isAvailable(platformRepo: PlatformRepo): Boolean = isSupported &&
        (ContestedUsernameFees.currentProtocolVersion >= MIN_PROTOCOL_VERSION ||
            platformRepo.refreshProtocolVersion() >= MIN_PROTOCOL_VERSION)
}
