/* Copyright 2026 Dash Core Group. SPDX-License-Identifier: GPL-3.0-or-later */
package de.schildbach.wallet.ui.more.connections

import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.service.platform.IdentityRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.map
import org.dash.wallet.common.WalletDataProvider
import javax.inject.Inject
import javax.inject.Singleton

enum class ConnectIdentityStatus { LOADING, AVAILABLE, NO_IDENTITY, UNAVAILABLE }

/** Only successful direct storage reads can establish that there is nothing to recover. */
@Singleton
class ConnectIdentityAvailability @Inject constructor(
    private val identities: IdentityRepository,
    private val identityConfig: BlockchainIdentityConfig,
    private val connections: DashConnectConfig,
    private val walletData: WalletDataProvider
) {
    fun observe(): Flow<ConnectIdentityStatus> = combine(
        identities.initializationResult, identities.blockchainIdentityFlow,
        identityConfig.observeIdentityPresence(), connections.observeHasRecords()
    ) { initialized, identity, persistedIdentity, hasRecords ->
        val status = when {
            initialized == null -> ConnectIdentityStatus.LOADING
            initialized.isFailure || walletData.wallet == null -> ConnectIdentityStatus.UNAVAILABLE
            identity == null && !persistedIdentity && !hasRecords -> ConnectIdentityStatus.NO_IDENTITY
            else -> ConnectIdentityStatus.AVAILABLE
        }
        identity to status
    }.distinctUntilChanged().map { it.second }.onStart { emit(ConnectIdentityStatus.LOADING) }.catch { error ->
        if (error is CancellationException) throw error
        org.slf4j.LoggerFactory.getLogger(ConnectIdentityAvailability::class.java)
            .error("Cannot establish identity or saved connection availability", error)
        emit(ConnectIdentityStatus.UNAVAILABLE)
    }
}
