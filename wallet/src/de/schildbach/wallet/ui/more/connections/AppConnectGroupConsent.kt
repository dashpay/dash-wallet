/* Copyright 2026 Dash Core Group. SPDX-License-Identifier: GPL-3.0-or-later */
package de.schildbach.wallet.ui.more.connections

import org.bitcoinj.core.Base58
import org.dashj.platform.dpp.contract.ContractGroupDocumentMember
import org.dashj.platform.dpp.contract.ContractGroupTokenMember
import org.dashj.platform.dpp.contract.VerifiedContractGroup
import org.dashj.platform.dpp.identifier.Identifier
import org.json.JSONArray
import org.json.JSONObject

private fun <T> JSONArray.uniqueSet(decode: (Int) -> T): Set<T> {
    val entries = (0 until length()).map(decode)
    return entries.toSet().also { check(it.size == entries.size) { "Duplicate stored group members" } }
}

/** Missing legacy metadata requires new consent; malformed metadata is never treated as missing. */
internal fun readApprovedGroupScopes(record: JSONObject?): Map<String, VerifiedContractGroup>? {
    if (record == null || !record.has("groupScopes")) return null
    val scopes = record.getJSONObject("groupScopes")
    return scopes.keys().asSequence().associateWith { id ->
        val group = scopes.getJSONObject(id)
        fun ids(field: String): Set<Identifier> = group.getJSONArray(field).let { values ->
            values.uniqueSet { Identifier.from(values.getString(it)) }
        }
        val documents = group.getJSONArray("documentTypes")
        val tokens = group.getJSONArray("tokens")
        VerifiedContractGroup(Identifier.from(id), Identifier.from(group.getString("ownerId")), ids("adminIds"),
            null, null, ids("contracts"), documents.uniqueSet { index ->
                documents.getJSONObject(index).let {
                    ContractGroupDocumentMember(Identifier.from(it.getString("contractId")), it.getString("documentType"))
                }
            }, tokens.uniqueSet { index ->
                tokens.getJSONObject(index).let {
                    val raw = it.get("tokenPosition")
                    check(raw is Int || raw is Long) { "Invalid stored token position" }
                    val position = (raw as Number).toLong()
                    check(position in 0L..65535L) { "Invalid stored token position" }
                    ContractGroupTokenMember(Identifier.from(it.getString("contractId")), position.toInt())
                }
            })
    }
}

private fun VerifiedContractGroup?.authorizationScope() = this?.copy(name = null, description = null)

private fun AppConnectPlan.selectedGroups() = selectedAuth.map { grants[it] }.filter { it.kind == 2 }
    .map { Base58.encode(it.id) }.toSet()

internal val AppConnectPlan.requiresGroupReapproval: Boolean
    get() = recovering && selectedGroups().any {
        previousGroupScopes?.get(it) == null ||
            previousGroupScopes?.get(it).authorizationScope() != groupScopes[it].authorizationScope()
    }

/** Bind the displayed recovery terms to the saved approval, not just another fresh network read. */
internal fun AppConnectPlan.requireStoredGroupConsent(record: JSONObject?) {
    val ids = selectedGroups()
    if (ids.isEmpty()) return
    val stored = readApprovedGroupScopes(record)
    if (recovering != (record != null) || ids.any {
        previousGroupScopes?.get(it).authorizationScope() != stored?.get(it).authorizationScope()
    }) throw GroupScopeChangedException()
    check(!requiresGroupReapproval || groupScopeChangeApproved) {
        "Acknowledge the updated group scope before approving this recovered request."
    }
}
