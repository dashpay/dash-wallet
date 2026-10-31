/* Copyright 2026 Dash Core Group. SPDX-License-Identifier: GPL-3.0-or-later */
package de.schildbach.wallet.ui.more.connections

import de.schildbach.wallet.ui.more.connections.protocol.AuthBinding
import de.schildbach.wallet.ui.more.connections.protocol.DashKeyRequest
import java.math.BigInteger
import org.dashj.platform.dpp.contract.VerifiedContractGroup

internal fun formatDash(credits: BigInteger): String = credits.toBigDecimal()
    .movePointLeft(11).stripTrailingZeros().toPlainString()

internal object AppConnectLimits {
    private const val DAY = 86_400_000L
    private val MAX_BUDGET = BigInteger("500000000000")

    fun clamp(grant: AuthBinding, blockTime: Long, requireUsableExpiry: Boolean = true): AuthBinding = grant.copy(
        totalBudget = grant.totalBudget?.min(MAX_BUDGET),
        expiresAt = minOf(grant.expiresAt ?: (blockTime + 90 * DAY), blockTime + 365 * DAY)
    ).also { require(!requireUsableExpiry || it.expiresAt!! > blockTime + 3_600_000) { "Requested grant expires too soon" } }
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
    val lifetimePreset: Int = 0,
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
        BigInteger("500000000000"))
    val lifetimeDays = listOf(30L, 90L, 365L)
}

/** Caps every proposed grant at the chosen presets; a preset never raises what the app requested. */
internal fun AppConnectPlan.withPresets(budget: Int, lifetime: Int): AppConnectPlan {
    require(budget in AppConnectPresets.budgets.indices && lifetime in AppConnectPresets.lifetimeDays.indices && !recovering)
    val credits = AppConnectPresets.budgets[budget]
    val expiry = blockTime + AppConnectPresets.lifetimeDays[lifetime] * 86_400_000L
    return copy(grants = proposedGrants.map { grant -> grant.copy(
        totalBudget = grant.totalBudget?.min(credits) ?: credits,
        expiresAt = minOf(grant.expiresAt!!, expiry)
    ) }, budgetPreset = budget, lifetimePreset = lifetime)
}

/**
 * Selects the smallest presets at or above the app's request, so the opening sheet grants exactly what
 * was requested until the user lowers it. An unlimited budget falls to the largest preset.
 */
internal fun AppConnectPlan.withRequestedPresets(): AppConnectPlan {
    val budgets = AppConnectPresets.budgets
    val requestedBudget = proposedGrants.map { it.totalBudget }.let { if (null in it) null else it.filterNotNull().maxOrNull() }
    val budget = requestedBudget?.let { max -> budgets.indexOfFirst { it >= max } }?.takeIf { it >= 0 } ?: budgets.lastIndex
    val requestedLifetime = proposedGrants.maxOfOrNull { it.expiresAt!! - blockTime } ?: 0L
    val lifetime = AppConnectPresets.lifetimeDays.indexOfFirst { it * 86_400_000L >= requestedLifetime }
        .takeIf { it >= 0 } ?: AppConnectPresets.lifetimeDays.lastIndex
    return withPresets(budget, lifetime)
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

/** The largest budget across the approved grants; null when any of them is unlimited or nothing is budgeted. */
internal fun AppConnectPlan.fundingLimit(): BigInteger? = selectedAuth.map { grants[it].totalBudget }
    .let { if (it.isEmpty() || null in it) null else it.filterNotNull().max() }

internal fun AppConnectPlan.connectionExpiresAt(): Long? = selectedAuth.mapNotNull { grants[it].expiresAt }.maxOrNull()

/** What an existing connection's recorded keys allow, for the connection details sheet. */
data class ConnectionTerms(
    val capabilities: List<AppCapability>,
    /** The largest key budget, matching the approve sheet; null when a key is unlimited. */
    val fundingLimit: BigInteger?,
    val expiresAt: Long?
)

internal fun recordedTerms(keys: List<org.dashj.platform.dpp.identity.IdentityPublicKey>): ConnectionTerms {
    val authentication = keys.filter { it.purpose == org.dashj.platform.sdk.Purpose.AUTHENTICATION }
    val capabilities = authentication.map { key ->
        val bounds = key.contractBounds
        val documentType = (bounds as? org.dashj.platform.dpp.identity.SingleContractDocumentType)?.documentType
        val kind = when {
            documentType == "profile" && bounds?.identifier?.toString() == DASHPAY_CONTRACT -> AppCapabilityKind.EDIT_DASHPAY_PROFILE
            documentType != null -> AppCapabilityKind.EDIT_DOCUMENTS
            else -> AppCapabilityKind.ACT_IN_APP
        }
        AppCapability(kind, tokens = kind == AppCapabilityKind.ACT_IN_APP &&
            key.securityLevel == org.dashj.platform.sdk.SecurityLevel.CRITICAL,
            documentType = documentType, budget = key.totalBudget, expiresAt = key.expiresAt)
    }.distinct() + if (keys.any { it.purpose != org.dashj.platform.sdk.Purpose.AUTHENTICATION }) {
        listOf(AppCapability(AppCapabilityKind.RECEIVE_ENCRYPTED))
    } else emptyList()
    val budgets = authentication.map { it.totalBudget }
    return ConnectionTerms(
        capabilities,
        if (budgets.isEmpty() || null in budgets) null else budgets.filterNotNull().max(),
        authentication.mapNotNull { it.expiresAt }.maxOrNull()
    )
}

data class GrantChoice(val index: Int, val description: String, val selected: Boolean, val encryption: Boolean = false)

internal fun AppConnectPlan.requireMatches(request: DashKeyRequest) {
    val requested = request.requireAppConnect().auth
    require(grants.size == requested.size && blockTime > 0) { "Consent does not match request" }
    grants.zip(requested).forEach { (grant, raw) ->
        require(grant.kind == raw.kind && grant.id.contentEquals(raw.id) && grant.documentType == raw.documentType &&
            grant.securityLevel == raw.securityLevel) { "Consent bounds or security level changed" }
        require((grant.totalBudget == null && raw.totalBudget == null) || (grant.totalBudget != null &&
            grant.totalBudget.signum() > 0 && (raw.totalBudget == null || grant.totalBudget <= raw.totalBudget) &&
            grant.totalBudget <= BigInteger("500000000000"))) {
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
        if (grant.kind == 2) append(checkNotNull(groupScopes[org.bitcoinj.core.Base58.encode(grant.id)]) {
            "Verified group scope is missing"
        }.consentText())
        if (grant.securityLevel == 1) append("\nCan claim and spend tokens within these bounds and spend your Platform credits on token purchases within this budget. Purchase payments go to the purchased token's contract owner, which may differ from the app owner.")
    }, index in selectedAuth)
} + encryptionChoices.mapIndexed { index, text -> GrantChoice(index, text, index in selectedEncryption, true) }
