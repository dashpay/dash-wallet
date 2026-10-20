package de.schildbach.wallet.ui.more.connections

import de.schildbach.wallet.ui.more.connections.AppConnectKeyState.KeyState
import de.schildbach.wallet.ui.more.connections.AppConnectKeyState.Presence.*
import org.junit.Assert.*
import org.junit.Test

class AppConnectKeyStateTest {
    @Test fun `missing submitted key remains unresolved`() {
        assertTrue(AppConnectKeyState.unresolved(listOf(KeyState(2, true, true, MISSING))))
    }

    @Test fun `conflicting ID proves atomic registration cannot land`() {
        assertFalse(AppConnectKeyState.unresolved(listOf(
            KeyState(2, true, true, CONFLICTING), KeyState(3, true, true, MISSING))))
    }

    @Test fun `disabled and expired keys are settled`() {
        assertFalse(AppConnectKeyState.unresolved(listOf(KeyState(2, true, true, INACTIVE))))
        assertFalse(AppConnectKeyState.unresolved(listOf(KeyState(2, true, false, MISSING))))
    }

    @Test fun `unresolved and conflicting grants do not block other confirmed keys`() {
        val active = KeyState(1, true, true, LIVE)
        val pending = KeyState(2, true, true, MISSING)
        val collision = KeyState(3, true, true, CONFLICTING)
        val disabled = KeyState(4, true, true, INACTIVE)
        assertEquals(listOf(1), AppConnectKeyState.revocableIds(listOf(active, pending, collision, disabled, active)))
        assertTrue(AppConnectKeyState.unresolved(listOf(pending)))
        assertFalse(AppConnectKeyState.unresolved(listOf(collision)))
    }

    @Test fun `never revoke foreign or shared encryption keys`() {
        assertEquals(emptyList<Int>(), AppConnectKeyState.revocableIds(listOf(
            KeyState(1, true, true, CONFLICTING), KeyState(3, false, true, LIVE))))
        assertFalse(AppConnectKeyState.unresolved(listOf(KeyState(3, false, true, MISSING))))
    }
}
