package de.schildbach.wallet.ui.more.connections

import android.app.Application
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.dashj.platform.dpp.identity.Identity
import org.dashj.platform.dpp.identity.IdentityPublicKey
import org.dashj.platform.sdk.KeyType
import org.dashj.platform.sdk.Purpose
import org.dashj.platform.sdk.SecurityLevel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE,
    shadows = [AppConnectKeyStateTest.ShadowSdkEnums::class])
class IdentityUpdateBatchesTest {
    private fun key(id: Int) = IdentityPublicKey(id, KeyType.ECDSA_HASH160, Purpose.AUTHENTICATION,
        SecurityLevel.HIGH, null, ByteArray(20) { id.toByte() }, false)
    private fun identity(keys: MutableList<IdentityPublicKey>) = mockk<Identity> {
        every { publicKeys } returns keys
    }

    @Test fun `twelve revocations and eight fences respect both limits and refresh between batches`() = runTest {
        val keys = (0..11).map { key(it) }.toMutableList()
        val current = identity(keys)
        val batches = mutableListOf<Pair<Int, Int>>()
        var reads = 0
        updateIdentityInBatches((20..27).map { key(it) }, (0..11).toList(), read = { reads++; current }) { _, add, disable ->
            batches.add(add.size to disable.size)
            keys.addAll(add)
            keys.filter { it.id in disable }.forEach { it.disabledAt = 1 }
        }
        assertEquals(listOf(6 to 10, 2 to 2), batches)
        assertTrue(reads >= 4)
        assertTrue(keys.filter { it.id < 12 }.all { it.disabledAt != null })
    }

    @Test fun `partial submission failure stops and a retry skips confirmed disables`() = runTest {
        val keys = (0..24).map { key(it) }.toMutableList()
        val current = identity(keys)
        var submissions = 0
        try {
            updateIdentityInBatches(emptyList(), (0..24).toList(), { current }) { _, _, disable ->
                if (++submissions == 2) error("transport failed")
                keys.filter { it.id in disable }.forEach { it.disabledAt = 1 }
            }
            fail("Expected submission failure")
        } catch (error: IllegalStateException) { assertEquals("transport failed", error.message) }
        assertEquals(2, submissions)
        val retried = mutableListOf<Int>()
        updateIdentityInBatches(emptyList(), (0..24).toList(), { current }) { _, _, disable ->
            retried.addAll(disable)
            keys.filter { it.id in disable }.forEach { it.disabledAt = 1 }
        }
        assertEquals((10..24).toList(), retried)
    }

    @Test fun `unconfirmed first batch never submits later keys`() = runTest {
        val current = identity((0..11).map { key(it) }.toMutableList())
        var submissions = 0
        try {
            updateIdentityInBatches(emptyList(), (0..11).toList(), { current }) { _, _, _ -> submissions++ }
            fail("Expected confirmation failure")
        } catch (error: IllegalStateException) { assertTrue(error.message!!.contains("retained")) }
        assertEquals(1, submissions)
    }

    @Test fun `fence collision stops before submitting an unrelated key`() = runTest {
        val occupied = key(20).also { it.data.fill(99) }
        var submissions = 0
        try {
            updateIdentityInBatches(listOf(key(20)), emptyList(), { identity(mutableListOf(occupied)) }) { _, _, _ -> submissions++ }
            fail("Expected collision")
        } catch (error: IllegalStateException) { assertTrue(error.message!!.contains("occupied")) }
        assertEquals(0, submissions)
    }
}
