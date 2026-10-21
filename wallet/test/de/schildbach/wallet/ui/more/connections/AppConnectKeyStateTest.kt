package de.schildbach.wallet.ui.more.connections

import de.schildbach.wallet.ui.more.connections.AppConnectKeyState.KeyState
import de.schildbach.wallet.ui.more.connections.AppConnectKeyState.Presence.*
import org.junit.Assert.*
import org.junit.Test
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.dpp.identity.ContractGroupBounds
import org.dashj.platform.dpp.identity.IdentityPublicKey
import org.dashj.platform.sdk.KeyType
import org.dashj.platform.sdk.Purpose
import org.dashj.platform.sdk.SecurityLevel
import java.math.BigInteger
import android.app.Application
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE,
    shadows = [AppConnectKeyStateTest.ShadowSdkEnums::class])
class AppConnectKeyStateTest {
    // These tests exercise Kotlin key matching, not native SDK calls. Supply the enum
    // constants through a shadow so the host JVM does not need the Android JNI library.
    @Implements(className = "org.dashj.platform.sdk.dashsdkJNI", isInAndroidSdk = false)
    class ShadowSdkEnums {
        companion object {
            @JvmStatic @Implementation fun KeyType_ECDSA_SECP256K1_get(): Int = 0
            @JvmStatic @Implementation fun KeyType_BLS12_381_get(): Int = 1
            @JvmStatic @Implementation fun KeyType_ECDSA_HASH160_get(): Int = 2
            @JvmStatic @Implementation fun KeyType_BIP13_SCRIPT_HASH_get(): Int = 3
            @JvmStatic @Implementation fun KeyType_EDDSA_25519_HASH160_get(): Int = 4
            @JvmStatic @Implementation fun Purpose_AUTHENTICATION_get(): Int = 0
            @JvmStatic @Implementation fun Purpose_ENCRYPTION_get(): Int = 1
            @JvmStatic @Implementation fun Purpose_DECRYPTION_get(): Int = 2
            @JvmStatic @Implementation fun Purpose_TRANSFER_get(): Int = 3
            @JvmStatic @Implementation fun Purpose_SYSTEM_get(): Int = 4
            @JvmStatic @Implementation fun Purpose_VOTING_get(): Int = 5
            @JvmStatic @Implementation fun Purpose_OWNER_get(): Int = 6
            @JvmStatic @Implementation fun SecurityLevel_MASTER_get(): Int = 0
            @JvmStatic @Implementation fun SecurityLevel_CRITICAL_get(): Int = 1
            @JvmStatic @Implementation fun SecurityLevel_HIGH_get(): Int = 2
            @JvmStatic @Implementation fun SecurityLevel_MEDIUM_get(): Int = 3
        }
    }

    private val time = 1_790_000_000_000L

    private fun key(budget: Long = 1000, expiry: Long = time + 86_400_000,
                    data: ByteArray = ByteArray(20) { 1 }, disabledAt: Long? = null) = IdentityPublicKey(
        2, KeyType.ECDSA_HASH160, Purpose.AUTHENTICATION, SecurityLevel.CRITICAL,
        ContractGroupBounds(Identifier.from(ByteArray(32) { 7 })), data, false,
        disabledAt = disabledAt, totalBudget = BigInteger.valueOf(budget), expiresAt = expiry, version = 1
    )

    @Test fun `changed limits preserve revocation identity but fail exact grant confirmation`() {
        val stored = key()
        for (current in listOf(key(budget = 2000), key(expiry = time + 172_800_000))) {
            assertTrue(AppConnectKeyState.sameKeyIdentity(stored, current))
            assertFalse(AppConnectKeyState.sameKey(stored, current))
            val states = AppConnectKeyState.inspect(listOf(stored), listOf(current), time)
            assertEquals(LIVE, states.single().presence)
            assertEquals(listOf(stored.id), AppConnectKeyState.revocableIds(states))
        }
    }

    @Test fun `extended expired grant remains revocable and changed material remains foreign`() {
        val expired = key(expiry = time - 1)
        val renewed = key()
        assertEquals(listOf(2), AppConnectKeyState.revocableIds(
            AppConnectKeyState.inspect(listOf(expired), listOf(renewed), time)))
        val foreign = key(data = ByteArray(20) { 2 })
        val states = AppConnectKeyState.inspect(listOf(renewed), listOf(foreign), time)
        assertEquals(CONFLICTING, states.single().presence)
        assertTrue(AppConnectKeyState.revocableIds(states).isEmpty())
        assertEquals(INACTIVE, AppConnectKeyState.inspect(listOf(renewed),
            listOf(key(budget = 2000, disabledAt = time)), time).single().presence)
    }

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
