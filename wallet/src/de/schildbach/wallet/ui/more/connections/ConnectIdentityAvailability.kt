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
import de.schildbach.wallet.data.WalletData
import javax.inject.Inject
import javax.inject.Singleton

enum class ConnectIdentityStatus { LOADING, AVAILABLE, NO_IDENTITY, UNAVAILABLE }

/** Only successful direct storage reads can establish that there is nothing to recover. */
@Singleton
class ConnectIdentityAvailability @Inject constructor(
    private val identities: IdentityRepository,
    private val identityConfig: BlockchainIdentityConfig,
    private val connections: DashConnectConfig,
    private val walletData: WalletData
) {
    /**
     * Retained records count only for the wallet that wrote them: a wiped wallet's grants stay on disk for
     * a restore of its seed, and must not make a new wallet without a username look connected.
     */
    fun observe(): Flow<ConnectIdentityStatus> = combine(
        identities.initializationResult, identities.blockchainIdentityFlow,
        identityConfig.observeIdentityPresence(), connections.observeRetainedRecords()
    ) { initialized, identity, persistedIdentity, records ->
        val wallet = walletData.wallet
        val status = when {
            initialized == null -> ConnectIdentityStatus.LOADING
            initialized.isFailure || wallet == null -> ConnectIdentityStatus.UNAVAILABLE
            identity == null && !persistedIdentity && !records.belongTo(connectWalletScope(wallet)) ->
                ConnectIdentityStatus.NO_IDENTITY
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
