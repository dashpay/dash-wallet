/*
 * Copyright 2026 Dash Core Group.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.schildbach.wallet.ui.more.connections

import de.schildbach.wallet.Constants
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.service.platform.PlatformService
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.more.connections.protocol.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withContext
import org.bitcoinj.core.NetworkParameters
import org.dashj.platform.sdk.platform.DomainDocument
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlatformDashConnectRepository @Inject constructor(
    private val platform: PlatformService,
    private val platformRepo: PlatformRepo,
    private val identityRepository: IdentityRepository,
    private val config: DashConnectConfig
) : DashConnectRepository {
    companion object { const val KEY_EXCHANGE_CONTRACT_ID = AppConnectSession.RESPONSE_CONTRACT }
    private val session = AppConnectSession(platform, platformRepo, identityRepository, config)

    override fun observeConnections(): Flow<List<DAppConnection>> = observeConnections(System::currentTimeMillis)

    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun observeConnections(now: () -> Long): Flow<List<DAppConnection>> = combine(
        config.observeGrants(), config.observeConnections(), identityRepository.blockchainIdentityFlow
    ) { records, legacy, _ -> records to legacy }.transformLatest { (records, legacy) ->
        // A record/identity update cancels the previous expiry schedule.
        while (true) {
            val currentTime = now()
            val scope = session.scope()
            val current = records.filter { it.getString("scope") == scope }.groupBy { it.getString("app") }.map { (app, grants) ->
                val latest = grants.maxBy { it.optLong("updatedAt") }
                val authenticationExpiries = grants.filter { it.optString("state") == "active" }.flatMap { record ->
                    record.getJSONArray("keys").let { keys ->
                        (0 until keys.length()).map { keys.getJSONObject(it) }
                            .filter { it.getInt("purpose") == 0 }
                            .map { it.optLong("expiresAt", Long.MAX_VALUE) }
                    }
                }
                val active = authenticationExpiries.any { it > currentTime }
                val lastExpiry = authenticationExpiries.maxOrNull()
                val updatedAt = if (!active && lastExpiry != null && lastExpiry <= currentTime) {
                    maxOf(latest.optLong("updatedAt"), lastExpiry)
                } else latest.optLong("updatedAt")
                DAppConnection(app, unverifiedAppName(latest.optString("label"), app), "",
                    when {
                        active -> ConnectionStatus.ACTIVE
                        // Revoked request keys do not prove that the app has no surviving access.
                        grants.any { it.optString("state") != "disabled" || !it.optBoolean("permanentRevocationConfirmed") ||
                            !it.optBoolean("appRevocationConfirmed") } -> ConnectionStatus.APPROVED
                        else -> ConnectionStatus.DISCONNECTED
                    }, updatedAt, scope)
            }
            emit((current + legacy.filter { old -> current.none { it.id == old.contractId } }.map {
                DAppConnection(it.contractId, it.label, it.url, ConnectionStatus.APPROVED, it.updatedAt, scope, legacyOnly = true)
            }).sortedByDescending { it.updatedAt })
            val nextExpiry = records.asSequence()
                .filter { it.getString("scope") == scope && it.optString("state") == "active" }
                .flatMap { record -> record.getJSONArray("keys").let { keys ->
                    (0 until keys.length()).asSequence().map { keys.getJSONObject(it) }
                } }
                .filter { it.getInt("purpose") == 0 }
                .map { it.optLong("expiresAt", Long.MAX_VALUE) }
                .filter { it > currentTime && it != Long.MAX_VALUE }
                .minOrNull() ?: break
            delay((nextExpiry - now()).coerceAtLeast(1L))
        }
    }

    override suspend fun parseQr(qrContent: String): DashConnectQr {
        checkNetwork()
        if (!DashConnectUri.isKeyUri(qrContent.trim())) {
            throw DashConnectUriException("Not a DashConnect QR code")
        }
        val request = DashConnectUri.parseKeyRequest(qrContent.trim())
        val expected = if (Constants.NETWORK_PARAMETERS.id == NetworkParameters.ID_TESTNET)
            DashConnectNetwork.TESTNET else DashConnectNetwork.DEVNET
        if (request.network != expected) throw DashConnectUriException("QR network does not match wallet network")
        request.appConnect?.requireFresh(System.currentTimeMillis())
        return DashConnectQr(request)
    }

    override suspend fun prepareLogin(request: DashKeyRequest): AppConnectPlan {
        checkNetwork()
        return session.prepare(request)
    }

    override suspend fun prepareTokenPurchase(request: de.schildbach.wallet.ui.more.connections.protocol.DashStRequest): TokenPurchaseApproval {
        checkNetwork()
        val expected = if (Constants.NETWORK_PARAMETERS.id == NetworkParameters.ID_TESTNET)
            DashConnectNetwork.TESTNET else DashConnectNetwork.DEVNET
        check(request.network == expected) { "QR network does not match wallet network" }
        return session.prepareTokenPurchase(request)
    }

    override suspend fun approveTokenPurchase(approval: TokenPurchaseApproval) {
        checkNetwork()
        session.submitTokenPurchase(approval)
    }

    override suspend fun setupPurchaseSigningKey(approval: TokenPurchaseApproval) {
        checkNetwork()
        session.setupPurchaseSigningKey(approval)
    }

    override suspend fun approveLogin(request: DashKeyRequest): DAppConnection =
        error("Explicit approval of prepared grant terms is required")

    override suspend fun approvePreparedLogin(request: DashKeyRequest, plan: AppConnectPlan?): DAppConnection {
        checkNetwork()
        return session.approve(request, requireNotNull(plan) { "Scan QR again to prepare approval" })
    }

    override suspend fun disconnect(connectionId: String, scope: String?) {
        checkNetwork()
        session.disconnect(connectionId, requireNotNull(scope) { "Refresh connections before revoking access" })
    }
    override suspend fun revokePreparedLogin(request: DashKeyRequest, plan: AppConnectPlan?) {
        checkNetwork()
        session.revokePrepared(request, requireNotNull(plan) { "Approval terms unavailable" })
    }
    override suspend fun connectionDetails(connectionId: String, scope: String?): String =
        if (config.getGrants().none { it.getString("scope") == scope && it.getString("app") == connectionId }) {
            "This legacy connection has no recorded grant keys. Removing it from this list does not revoke any Platform authorization."
        } else session.details(connectionId, requireNotNull(scope) { "Refresh connections before viewing grants" })
    override suspend fun removeConnection(connectionId: String, scope: String?) {
        check(scope == runCatching { session.scope() }.getOrNull()) { "Wallet, identity or network changed" }
        config.removeLegacyConnection(connectionId, scope)
    }

    override suspend fun resolveWalletUsername(): String? = withContext(Dispatchers.IO) {
        try {
            identityRepository.blockchainIdentity?.uniqueIdentifier?.let { id ->
                platform.names.getByOwnerId(id).firstOrNull()?.let { DomainDocument(it).label }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
    }

    override suspend fun resolveAppDisplay(contractId: ByteArray, qrLabel: String): AppDisplay =
        // There are no pinned brand IDs yet. Neither a QR label nor an editable profile proves a brand.
        AppDisplay("\"${qrLabel.ifBlank { "Unknown app" }}\"", "")

    private fun checkNetwork() {
        check(!Constants.IS_PROD_BUILD && Constants.NETWORK_PARAMETERS.id != NetworkParameters.ID_MAINNET) {
            "App Connect is unavailable in production or on mainnet"
        }
    }
}
