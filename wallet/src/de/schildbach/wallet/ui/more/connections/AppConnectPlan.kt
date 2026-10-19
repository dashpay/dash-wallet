/* Copyright 2026 Dash Core Group. SPDX-License-Identifier: GPL-3.0-or-later */
package de.schildbach.wallet.ui.more.connections

import de.schildbach.wallet.ui.more.connections.protocol.AuthBinding
import de.schildbach.wallet.ui.more.connections.protocol.DashKeyRequest
import java.math.BigInteger

internal fun formatDash(credits: BigInteger): String = credits.toBigDecimal()
    .movePointLeft(11).stripTrailingZeros().toPlainString()

internal object AppConnectLimits {
    private const val DAY = 86_400_000L
    private val MAX_BUDGET = BigInteger("500000000000")

    fun clamp(grant: AuthBinding, blockTime: Long): AuthBinding = grant.copy(
        totalBudget = grant.totalBudget?.min(MAX_BUDGET),
        expiresAt = minOf(grant.expiresAt ?: (blockTime + 90 * DAY), blockTime + 365 * DAY)
    ).also { require(it.expiresAt!! > blockTime + 3_600_000) { "Requested grant expires too soon" } }
}

/** The immutable terms presented for explicit consent. No private keys are kept here. */
data class AppConnectPlan(
    val requestHash: String,
    val scope: String,
    val identityId: String,
    val grants: List<AuthBinding>,
    val permissionText: String,
    val selectedAuth: Set<Int> = grants.indices.toSet(),
    val encryptionChoices: List<String> = emptyList(),
    val selectedEncryption: Set<Int> = encryptionChoices.indices.toSet(),
    val recovering: Boolean = false,
    val blockTime: Long = 0,
    val proposedGrants: List<AuthBinding> = grants,
    val budgetPreset: Int = 0,
    val lifetimePreset: Int = 0
)

internal fun AppConnectPlan.withPresets(budget: Int, lifetime: Int): AppConnectPlan {
    require(budget in 0..3 && lifetime in 0..3 && !recovering)
    val credits = listOf(null, BigInteger("10000000000"), BigInteger("50000000000"), BigInteger("100000000000"))[budget]
    val days = listOf(null, 30L, 90L, 365L)[lifetime]
    return copy(grants = proposedGrants.map { grant -> grant.copy(
        totalBudget = if (credits == null) grant.totalBudget else grant.totalBudget?.min(credits),
        expiresAt = if (days == null) grant.expiresAt else minOf(grant.expiresAt!!, blockTime + days * 86_400_000L)
    ) }, budgetPreset = budget, lifetimePreset = lifetime)
}

data class GrantChoice(val index: Int, val description: String, val selected: Boolean, val encryption: Boolean = false)

internal fun AppConnectPlan.requireMatches(request: DashKeyRequest) {
    val requested = request.appConnect?.auth ?: listOf(AuthBinding(0, 2, request.contractId, BigInteger("5000000000"), null))
    require(grants.size == requested.size && blockTime > 0) { "Consent does not match request" }
    grants.zip(requested).forEach { (grant, raw) ->
        require(grant.kind == raw.kind && grant.id.contentEquals(raw.id) && grant.documentType == raw.documentType &&
            grant.securityLevel == raw.securityLevel) { "Consent bounds or security level changed" }
        require(if (raw.totalBudget == null) grant.totalBudget == null else grant.totalBudget != null &&
            grant.totalBudget.signum() > 0 && grant.totalBudget <= raw.totalBudget && grant.totalBudget <= BigInteger("500000000000")) {
            "Consent budget changed or was removed"
        }
        require(grant.expiresAt != null && grant.expiresAt <= blockTime + 365 * 86_400_000L &&
            (raw.expiresAt == null || grant.expiresAt <= raw.expiresAt)) { "Consent expiry changed or was removed" }
    }
}

internal fun AppConnectPlan.choices(): List<GrantChoice> = grants.mapIndexed { index, grant ->
    GrantChoice(index, buildString {
        append("${if (grant.securityLevel == 1) "CRITICAL" else "HIGH"} authentication\n")
        append("${when (grant.kind) { 2 -> "Contract group"; 1 -> "Document ${grant.documentType}"; else -> "Contract" }}: ${org.bitcoinj.core.Base58.encode(grant.id)}\n")
        append("Budget: ${grant.totalBudget?.let { "${formatDash(it)} DASH" } ?: "No budget limit"}\n")
        append("Expires: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(grant.expiresAt!!))}")
        if (grant.securityLevel == 1) append("\nCan claim and spend this app's tokens on your behalf.")
    }, index in selectedAuth)
} + encryptionChoices.mapIndexed { index, text -> GrantChoice(index, text, index in selectedEncryption, true) }
