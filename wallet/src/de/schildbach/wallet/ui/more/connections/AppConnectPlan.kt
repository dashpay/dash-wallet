/* Copyright 2026 Dash Core Group. SPDX-License-Identifier: GPL-3.0-or-later */
package de.schildbach.wallet.ui.more.connections

import de.schildbach.wallet.ui.more.connections.protocol.AuthBinding
import de.schildbach.wallet.ui.more.connections.protocol.DashKeyRequest
import java.math.BigInteger
import org.dashj.platform.dpp.contract.VerifiedContractGroup

internal fun formatDash(credits: BigInteger): String = credits.toBigDecimal()
    .movePointLeft(11).stripTrailingZeros().toPlainString()

internal object AppConnectLimits {
    const val DAY = 86_400_000L
    /** The most a connection may spend in total: 5 DASH, in credits. */
    val MAX_BUDGET = BigInteger("500000000000")
    /** A DashPay profile key's share of the funding limit: 0.002 DASH, at least 5 profile updates. */
    val PROFILE_BUDGET = BigInteger("200000000")
    const val MAX_LIFETIME = 365 * DAY
    const val MIN_LIFETIME = 3_600_000L

    fun clamp(grant: AuthBinding, blockTime: Long, requireUsableExpiry: Boolean = true): AuthBinding = grant.copy(
        totalBudget = grant.totalBudget?.min(MAX_BUDGET),
        expiresAt = minOf(grant.expiresAt ?: (blockTime + 90 * DAY), blockTime + MAX_LIFETIME)
    ).also { require(!requireUsableExpiry || it.expiresAt!! > blockTime + MIN_LIFETIME) { "Requested grant expires too soon" } }
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
    /** The funding limit for the whole connection, split between the selected keys; null until set. */
    val fundingTotal: BigInteger? = null,
    /** The one expiry shared by all of the app's keys; null until set. */
    val expiry: Long? = null,
    val groupScopes: Map<String, VerifiedContractGroup> = emptyMap(),
    val previousGroupScopes: Map<String, VerifiedContractGroup>? = null,
    val groupScopeChangeApproved: Boolean = false,
    /** When the app created the QR request, millis since epoch. */
    val requestedAt: Long = 0
)

internal class GroupScopeChangedException : IllegalStateException(
    "Contract group configuration changed. Review the refreshed scope and approve again."
)

internal fun AppConnectPlan.requireGroupScopes(current: Map<String, VerifiedContractGroup>) {
    val selected = selectedAuth.map { grants[it] }.filter { it.kind == 2 }
        .map { org.bitcoinj.core.Base58.encode(it.id) }.toSet()
    if (selected.any { groupScopes[it] == null || groupScopes[it].authorizationScope() != current[it].authorizationScope() }) {
        throw GroupScopeChangedException()
    }
}

internal fun AppConnectPlan.groupScopesJson(): org.json.JSONObject = org.json.JSONObject().also { result ->
    val ids = selectedAuth.map { grants[it] }.filter { it.kind == 2 }.map { org.bitcoinj.core.Base58.encode(it.id) }.toSet()
    groupScopes.filterKeys { it in ids }.forEach { (id, group) ->
        result.put(id, org.json.JSONObject().put("ownerId", group.ownerId.toString())
            .put("adminIds", org.json.JSONArray(group.adminIds.map { it.toString() }.sorted()))
            .put("contracts", org.json.JSONArray(group.contracts.map { it.toString() }.sorted()))
            .put("documentTypes", org.json.JSONArray(group.documentTypes.map {
                org.json.JSONObject().put("contractId", it.contractId.toString()).put("documentType", it.documentType)
            }))
            .put("tokens", org.json.JSONArray(group.tokens.map {
                org.json.JSONObject().put("contractId", it.contractId.toString()).put("tokenPosition", it.tokenPosition)
            })))
    }
}

private fun VerifiedContractGroup.consentText(): String = buildString {
    append("\nVerified group owner: $ownerId")
    append("\nAdmins who can add members: ${adminIds.map { it.toString() }.sorted().joinToString().ifEmpty { "None; owner only" }}")
    append("\nCurrent members:")
    contracts.map { it.toString() }.sorted().forEach { append("\nContract: $it (all document types and tokens)") }
    documentTypes.sortedWith(compareBy({ it.contractId.toString() }, { it.documentType })).forEach {
        val safeType = it.documentType.replace(Regex("[\\p{Cc}\\p{Cf}]"), "")
        append("\nDocument: ${it.contractId} / $safeType")
    }
    tokens.sortedWith(compareBy({ it.contractId.toString() }, { it.tokenPosition })).forEach {
        append("\nToken: ${it.contractId} / position ${it.tokenPosition}")
    }
    if (contracts.isEmpty() && documentTypes.isEmpty() && tokens.isEmpty()) append("\nNone")
    append("\nThis grant covers current AND future group members. The owner or any admin can add members without another wallet approval. Whole-contract membership also covers future document types and tokens in that contract.")
}

/** The identity funding limits (0.1, 0.5, 2 and 5 DASH, in credits) and expiration periods the sheet offers. */
internal object AppConnectPresets {
    val budgets = listOf(BigInteger("10000000000"), BigInteger("50000000000"), BigInteger("200000000000"),
        AppConnectLimits.MAX_BUDGET)
    val lifetimeDays = listOf(30L, 90L, 365L)
}

private fun AuthBinding.isDashPayProfile() =
    kind == 1 && documentType == "profile" && org.bitcoinj.core.Base58.encode(id) == DASHPAY_CONTRACT

/**
 * Sets one funding limit for the connection and one expiry for all of its keys. The limit is split
 * between the selected keys: a DashPay profile key gets 0.002 DASH (half the limit when it is under
 * 0.004 DASH) and the app's own keys share the rest. Either may be above or below the app's request.
 */
internal fun AppConnectPlan.withLimits(total: BigInteger, expiresAt: Long): AppConnectPlan {
    require(!recovering && total.signum() > 0 && total <= AppConnectLimits.MAX_BUDGET) { "Funding limit out of range" }
    require(expiresAt > blockTime + AppConnectLimits.MIN_LIFETIME && expiresAt <= blockTime + AppConnectLimits.MAX_LIFETIME) {
        "Expiry out of range"
    }
    val selected = selectedAuth.sorted()
    val profiles = selected.filter { proposedGrants[it].isDashPayProfile() }.takeIf { it.size < selected.size }.orEmpty()
    val others = selected - profiles.toSet()
    val profileShare = AppConnectLimits.PROFILE_BUDGET.min(total.divide(BigInteger.valueOf(selected.size.toLong())))
    val rest = total - profileShare * BigInteger.valueOf(profiles.size.toLong())
    val (each, remainder) = rest.divideAndRemainder(BigInteger.valueOf(others.size.toLong()))
    val budgets = profiles.associateWith { profileShare } +
        others.mapIndexed { position, index -> index to if (position == 0) each + remainder else each }
    // Unselected keys are not registered; they keep the whole limit so every proposal stays positive.
    return copy(grants = proposedGrants.mapIndexed { index, grant ->
        grant.copy(totalBudget = budgets[index] ?: total, expiresAt = expiresAt)
    }, fundingTotal = total, expiry = expiresAt)
}

/** Recomputes the split after the selected keys change. */
internal fun AppConnectPlan.withCurrentLimits(): AppConnectPlan =
    if (fundingTotal == null || expiry == null || recovering) this else withLimits(fundingTotal, expiry)

/**
 * Starts at what the app requested: the total of its keys' budgets capped at 5 DASH (5 DASH when a key
 * has no budget) and its expiry capped at a year (90 days when none was requested).
 */
internal fun AppConnectPlan.withRequestedLimits(): AppConnectPlan {
    val selected = selectedAuth.sorted().map { proposedGrants[it] }
    val requested = selected.map { it.totalBudget }
    val total = if (null in requested) AppConnectLimits.MAX_BUDGET else
        requested.filterNotNull().fold(BigInteger.ZERO, BigInteger::add).min(AppConnectLimits.MAX_BUDGET)
    return withLimits(total, selected.maxOf { it.expiresAt!! })
}

enum class AppCapabilityKind { ACT_IN_APP, EDIT_DASHPAY_PROFILE, EDIT_DOCUMENTS, RECEIVE_ENCRYPTED }

/** One plain-language line of what an approved connection lets the app do. */
data class AppCapability(
    val kind: AppCapabilityKind,
    /** CRITICAL grants can claim and spend the app's tokens. */
    val tokens: Boolean = false,
    val documentType: String? = null,
    /** Credits of the identity balance the app may spend; null when unlimited. */
    val budget: BigInteger? = null,
    val expiresAt: Long? = null
)

private const val DASHPAY_CONTRACT = "Bwr4WHCPz5rFVAD87RqTs3izo4zpzwsEdKPWUT1NS1C7"

internal fun AppConnectPlan.capabilities(): List<AppCapability> = selectedAuth.sorted().map { grants[it] }.map { grant ->
    val kind = when {
        grant.kind == 1 && grant.documentType == "profile" && org.bitcoinj.core.Base58.encode(grant.id) == DASHPAY_CONTRACT ->
            AppCapabilityKind.EDIT_DASHPAY_PROFILE
        grant.kind == 1 -> AppCapabilityKind.EDIT_DOCUMENTS
        else -> AppCapabilityKind.ACT_IN_APP
    }
    AppCapability(kind, tokens = kind == AppCapabilityKind.ACT_IN_APP && grant.securityLevel == 1,
        documentType = grant.documentType, budget = grant.totalBudget, expiresAt = grant.expiresAt)
} + if (selectedEncryption.isNotEmpty()) listOf(AppCapability(AppCapabilityKind.RECEIVE_ENCRYPTED)) else emptyList()

/** The connection's funding limit: the approved keys' budgets together; null when any of them is unlimited. */
internal fun AppConnectPlan.fundingLimit(): BigInteger? = selectedAuth.map { grants[it].totalBudget }
    .let { if (it.isEmpty() || null in it) null else it.filterNotNull().fold(BigInteger.ZERO, BigInteger::add) }

internal fun AppConnectPlan.connectionExpiresAt(): Long? = selectedAuth.mapNotNull { grants[it].expiresAt }.maxOrNull()

/** What an existing connection's recorded keys allow, for the connection details sheet. */
data class ConnectionTerms(
    val capabilities: List<AppCapability>,
    /** The keys' budgets together, matching the approve sheet; null when a key is unlimited. */
    val fundingLimit: BigInteger?,
    val expiresAt: Long?,
    /** What each enabled, budgeted key of the connection has left; empty when none is enabled. */
    val keyFunding: List<KeyFunding> = emptyList()
)

/** One key's funding: its budget and what Platform says is left, null when that could not be read. */
data class KeyFunding(
    val kind: AppCapabilityKind,
    val documentType: String?,
    val budget: BigInteger,
    val remaining: BigInteger?
)

internal fun capabilityKind(key: org.dashj.platform.dpp.identity.IdentityPublicKey): AppCapabilityKind {
    val bounds = key.contractBounds
    val documentType = (bounds as? org.dashj.platform.dpp.identity.SingleContractDocumentType)?.documentType
    return when {
        documentType == "profile" && bounds?.identifier?.toString() == DASHPAY_CONTRACT -> AppCapabilityKind.EDIT_DASHPAY_PROFILE
        documentType != null -> AppCapabilityKind.EDIT_DOCUMENTS
        else -> AppCapabilityKind.ACT_IN_APP
    }
}

internal fun recordedTerms(keys: List<org.dashj.platform.dpp.identity.IdentityPublicKey>): ConnectionTerms {
    val authentication = keys.filter { it.purpose == org.dashj.platform.sdk.Purpose.AUTHENTICATION }
    val capabilities = authentication.map { key ->
        val documentType = (key.contractBounds as? org.dashj.platform.dpp.identity.SingleContractDocumentType)?.documentType
        val kind = capabilityKind(key)
        AppCapability(kind, tokens = kind == AppCapabilityKind.ACT_IN_APP &&
            key.securityLevel == org.dashj.platform.sdk.SecurityLevel.CRITICAL,
            documentType = documentType, budget = key.totalBudget, expiresAt = key.expiresAt)
    }.distinct() + if (keys.any { it.purpose != org.dashj.platform.sdk.Purpose.AUTHENTICATION }) {
        listOf(AppCapability(AppCapabilityKind.RECEIVE_ENCRYPTED))
    } else emptyList()
    val budgets = authentication.map { it.totalBudget }
    return ConnectionTerms(
        capabilities,
        if (budgets.isEmpty() || null in budgets) null else budgets.filterNotNull().fold(BigInteger.ZERO, BigInteger::add),
        // A key without an expiry keeps the connection authorized indefinitely.
        if (authentication.isEmpty() || authentication.any { it.expiresAt == null }) null
        else authentication.maxOf { it.expiresAt!! }
    )
}

data class GrantChoice(val index: Int, val description: String, val selected: Boolean, val encryption: Boolean = false)

internal fun AppConnectPlan.requireMatches(request: DashKeyRequest) {
    val requested = request.requireAppConnect().auth
    require(grants.size == requested.size && blockTime > 0) { "Consent does not match request" }
    grants.zip(requested).forEach { (grant, raw) ->
        require(grant.kind == raw.kind && grant.id.contentEquals(raw.id) && grant.documentType == raw.documentType &&
            grant.securityLevel == raw.securityLevel) { "Consent bounds or security level changed" }
        // The user sets the limits, above or below the request, so only the wallet's own bounds apply. A
        // recovering approval keeps what it was prepared with, which an earlier version left unlimited.
        require((recovering && grant.totalBudget == null) || (grant.totalBudget != null &&
            grant.totalBudget.signum() > 0 && grant.totalBudget <= AppConnectLimits.MAX_BUDGET)) {
            "Consent budget changed or was removed"
        }
        require(grant.expiresAt != null && grant.expiresAt <= blockTime + AppConnectLimits.MAX_LIFETIME) {
            "Consent expiry changed or was removed"
        }
    }
    // A recovering approval keeps the limits it was prepared with, which were capped per key.
    require(recovering || selectedAuth.mapNotNull { grants[it].totalBudget }.fold(BigInteger.ZERO, BigInteger::add) <=
        AppConnectLimits.MAX_BUDGET) { "Funding limit is above 5 DASH" }
}

internal fun AppConnectPlan.choices(): List<GrantChoice> = grants.mapIndexed { index, grant ->
    GrantChoice(index, buildString {
        append("${if (grant.securityLevel == 1) "CRITICAL" else "HIGH"} authentication\n")
        append("${when (grant.kind) { 2 -> "Contract group"; 1 -> "Document ${grant.documentType}"; else -> "Contract" }}: ${org.bitcoinj.core.Base58.encode(grant.id)}\n")
        append("Budget: ${grant.totalBudget?.let { "${formatDash(it)} DASH" } ?: "No budget limit"}\n")
        append("Expires: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(grant.expiresAt!!))}")
        if (grant.kind == 2) append(checkNotNull(groupScopes[org.bitcoinj.core.Base58.encode(grant.id)]) {
            "Verified group scope is missing"
        }.consentText())
        if (grant.securityLevel == 1) append("\nCan claim and spend tokens within these bounds and spend your Platform credits on token purchases within this budget. Purchase payments go to the purchased token's contract owner, which may differ from the app owner.")
    }, index in selectedAuth)
} + encryptionChoices.mapIndexed { index, text -> GrantChoice(index, text, index in selectedEncryption, true) }
