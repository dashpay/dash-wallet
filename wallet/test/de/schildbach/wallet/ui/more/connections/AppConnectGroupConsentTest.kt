package de.schildbach.wallet.ui.more.connections

import de.schildbach.wallet.ui.more.connections.protocol.AuthBinding
import org.dashj.platform.dpp.contract.ContractGroupDocumentMember
import org.dashj.platform.dpp.contract.ContractGroupTokenMember
import org.dashj.platform.dpp.contract.VerifiedContractGroup
import org.dashj.platform.dpp.identifier.Identifier
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

class AppConnectGroupConsentTest {
    private val id = Identifier.from(ByteArray(32) { 1 })
    private val other = Identifier.from(ByteArray(32) { 2 })
    private val group = VerifiedContractGroup(id, id, setOf(id, other), "Label", "Description", setOf(id, other),
        setOf(ContractGroupDocumentMember(id, "profile")), setOf(ContractGroupTokenMember(id, 0)))
    private val plan = AppConnectPlan("request", "scope", "identity", listOf(
        AuthBinding(2, 1, id.toBuffer(), BigInteger.ONE, 1_900_000_000_000)
    ), "Consent", groupScopes = mapOf(id.toString() to group))
    private fun record() = JSONObject().put("groupScopes", plan.groupScopesJson())
    private fun recovery(record: JSONObject = record()) = plan.copy(recovering = true,
        previousGroupScopes = readApprovedGroupScopes(record))

    @Test fun `fresh scope comparison ignores descriptive metadata changes`() {
        for (changed in listOf(group.copy(name = "Renamed"), group.copy(description = "Reworded"),
            group.copy(name = null, description = null))) {
            plan.requireGroupScopes(mapOf(id.toString() to changed))
        }
    }

    @Test fun `fresh scope comparison still rejects all authorization changes and missing scopes`() {
        for (changed in listOf(group.copy(id = other), group.copy(ownerId = other), group.copy(adminIds = emptySet()),
            group.copy(contracts = emptySet()), group.copy(documentTypes = emptySet()), group.copy(tokens = emptySet()))) {
            assertThrows(GroupScopeChangedException::class.java) {
                plan.requireGroupScopes(mapOf(id.toString() to changed))
            }
        }
        assertThrows(GroupScopeChangedException::class.java) { plan.requireGroupScopes(emptyMap()) }
        assertThrows(GroupScopeChangedException::class.java) { plan.copy(groupScopes = emptyMap()).requireGroupScopes(emptyMap()) }
        plan.copy(selectedAuth = emptySet()).requireGroupScopes(emptyMap())
    }

    @Test fun `unchanged recovery accepts the saved approval regardless of member ordering or labels`() {
        val saved = record()
        saved.getJSONObject("groupScopes").getJSONObject(id.toString())
            .put("adminIds", JSONArray(listOf(other.toString(), id.toString())))
            .put("contracts", JSONArray(listOf(other.toString(), id.toString())))
        val restored = recovery(saved)
        assertFalse(restored.requiresGroupReapproval)
        restored.requireStoredGroupConsent(saved)
        assertEquals(group.copy(name = null, description = null), restored.previousGroupScopes!![id.toString()])
    }

    @Test fun `every authorization change requires new consent and acknowledgement`() {
        val saved = record()
        for (changed in listOf(group.copy(ownerId = other), group.copy(adminIds = emptySet()),
            group.copy(contracts = emptySet()), group.copy(documentTypes = emptySet()), group.copy(tokens = emptySet()))) {
            val restored = recovery(saved).copy(groupScopes = mapOf(id.toString() to changed))
            assertTrue(restored.requiresGroupReapproval)
            assertThrows(IllegalStateException::class.java) { restored.requireStoredGroupConsent(saved) }
            restored.copy(groupScopeChangeApproved = true).requireStoredGroupConsent(saved)
        }
    }

    @Test fun `missing saved scope cannot silently become a fresh approval`() {
        val legacy = JSONObject()
        val restored = recovery(legacy)
        assertTrue(restored.requiresGroupReapproval)
        assertThrows(IllegalStateException::class.java) { restored.requireStoredGroupConsent(legacy) }
        restored.copy(groupScopeChangeApproved = true).requireStoredGroupConsent(legacy)
        assertThrows(GroupScopeChangedException::class.java) { plan.requireStoredGroupConsent(legacy) }
    }

    @Test fun `stored approval changing after preparation invalidates even an acknowledged plan`() {
        val saved = record()
        val restored = recovery(saved).copy(groupScopeChangeApproved = true)
        val changed = plan.copy(groupScopes = mapOf(id.toString() to group.copy(adminIds = emptySet())))
        assertThrows(GroupScopeChangedException::class.java) {
            restored.requireStoredGroupConsent(JSONObject().put("groupScopes", changed.groupScopesJson()))
        }
        assertThrows(GroupScopeChangedException::class.java) { restored.requireStoredGroupConsent(null) }
    }

    @Test fun `new approvals and unselected groups do not need recovery acknowledgement`() {
        assertFalse(plan.requiresGroupReapproval)
        plan.requireStoredGroupConsent(null)
        val unselected = recovery().copy(selectedAuth = emptySet())
        assertFalse(unselected.requiresGroupReapproval)
        unselected.requireStoredGroupConsent(null)
    }

    @Test fun `malformed incomplete duplicate or invalid saved data fails closed`() {
        for (change in listOf<(JSONObject) -> Unit>(
            { it.remove("tokens") },
            { it.put("adminIds", JSONArray(listOf(id.toString(), id.toString()))) },
            { it.put("ownerId", "invalid") },
            { it.put("tokens", JSONArray().put(JSONObject().put("contractId", id.toString()).put("tokenPosition", -1))) }
        )) {
            val saved = record()
            change(saved.getJSONObject("groupScopes").getJSONObject(id.toString()))
            assertThrows(Exception::class.java) { readApprovedGroupScopes(saved) }
        }
    }
}
