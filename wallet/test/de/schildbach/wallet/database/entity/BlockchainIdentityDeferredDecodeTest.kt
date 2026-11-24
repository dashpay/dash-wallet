/*
 * Copyright 2026 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.schildbach.wallet.database.entity

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import de.schildbach.wallet.service.platform.PlatformService
import de.schildbach.wallet.service.platform.RequestedNameContext
import de.schildbach.wallet.service.platform.serializeIdentityToleratingContractBounds
import de.schildbach.wallet.ui.more.connections.AppConnectKeyStateTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.dashj.platform.dashpay.UsernameRequestStatus
import org.dashj.platform.dpp.DashPlatformProtocol
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.dpp.identity.Identity
import org.dashj.platform.dpp.identity.IdentityFactory
import org.dashj.platform.dpp.identity.IdentityPublicKey
import org.dashj.platform.dpp.identity.SingleContractDocumentType
import org.dashj.platform.dpp.toHex
import org.dashj.platform.dpp.util.Converters
import org.dashj.platform.sdk.KeyType
import org.dashj.platform.sdk.Purpose
import org.dashj.platform.sdk.SecurityLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The stored identity blob is decoded (which constructs the legacy Platform client) only when
 * [BlockchainIdentityData.identity] is read, over the real DataStore-backed [BlockchainIdentityConfig].
 * The decode is the real legacy one (a spied [IdentityFactory]); the SDK enums come from a shadow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    application = Application::class,
    sdk = [29],
    manifest = Config.NONE,
    shadows = [AppConnectKeyStateTest.ShadowSdkEnums::class]
)
class BlockchainIdentityDeferredDecodeTest {
    private val userId = "6XqBkTZTUnDMcgGvKzs5NRotZbAMBjKhJ4bQzKzXcCwr"
    private val factory = spyk(IdentityFactory(mockk(relaxed = true), mockk(relaxed = true)))
    private val dpp = mockk<DashPlatformProtocol> { every { identity } returns factory }
    private val platformService = mockk<PlatformService> { every { dpp } returns this@BlockchainIdentityDeferredDecodeTest.dpp }

    private fun config() = BlockchainIdentityConfig(
        ApplicationProvider.getApplicationContext(),
        mockk(relaxed = true),
        platformService
    ).also { runBlocking { it.clear() } }

    private fun key(id: Int, purpose: Purpose, level: SecurityLevel, bounds: SingleContractDocumentType? = null) =
        IdentityPublicKey(id, KeyType.ECDSA_SECP256K1, purpose, level, bounds, ByteArray(33) { (id + 2).toByte() }, false)

    private fun identity(seed: Int, bounded: Boolean = false): Identity {
        val bounds = if (bounded) SingleContractDocumentType(Identifier.from(ByteArray(32) { 9 }), "contactRequest") else null
        return Identity(
            Identifier.from(ByteArray(32) { seed.toByte() }),
            listOf(
                key(0, Purpose.AUTHENTICATION, SecurityLevel.MASTER),
                key(1, Purpose.AUTHENTICATION, SecurityLevel.HIGH),
                key(4, Purpose.ENCRYPTION, SecurityLevel.MEDIUM, bounds)
            ),
            1000L,
            1,
            1
        )
    }

    private fun record(identity: Identity? = null) =
        BlockchainIdentityData(IdentityCreationState.DONE, null, "alice", null, userId, false, identity = identity)

    private suspend fun BlockchainIdentityConfig.storedHex(): String? = get(BlockchainIdentityConfig.IDENTITY)

    /** Another writer replaces or removes the stored blob. */
    private suspend fun BlockchainIdentityConfig.writeHex(hex: String?) =
        if (hex == null) remove(BlockchainIdentityConfig.IDENTITY) else set(BlockchainIdentityConfig.IDENTITY, hex)

    /** Stores a record with [identity] (encoded by today's write path) and returns its hex. */
    private suspend fun BlockchainIdentityConfig.store(identity: Identity): String {
        insert(record(identity))
        return requireNotNull(storedHex())
    }

    private fun assertNoDecode() {
        verify(exactly = 0) { platformService.dpp }
        verify(exactly = 0) { factory.createFromBuffer(any(), any()) }
    }

    @Test
    fun `load, observe and the recovery seed do not decode until the identity is read`() = runBlocking {
        val config = config()
        val original = identity(1)
        config.store(original)

        val loaded = requireNotNull(config.load())
        val observed = config.observe().first()
        config.recordRequestedNames(RequestedNameContext(userId, "alice", null))
        val seed = requireNotNull(config.loadRecoverySeed(userId, fromCreation = false))
        assertEquals(userId, loaded.userId)
        assertNoDecode()

        assertEquals(original.id, loaded.identity?.id)
        assertEquals(original.id, observed.identity?.id)
        assertEquals(original.publicKeys.map { it.id }, observed.identity?.publicKeys?.map { it.id })
        assertEquals(original.id, seed.identity?.id)
        verify(exactly = 3) { factory.createFromBuffer(any(), any()) }
    }

    @Test
    fun `repeated and concurrent reads of one record decode once`() = runBlocking {
        val config = config()
        config.store(identity(2))
        val loaded = requireNotNull(config.load())

        val reads = (1..16).map { async(Dispatchers.Default) { loaded.identity } }.awaitAll()
        val again = loaded.identity

        reads.forEach { assertSame(again, it) }
        verify(exactly = 1) { factory.createFromBuffer(any(), any()) }
    }

    @Test
    fun `saving an unread record writes the loaded blob back without decoding`() = runBlocking {
        val config = config()
        val hex = config.store(identity(3))
        val loaded = requireNotNull(config.load())

        loaded.usernameRequested = UsernameRequestStatus.VOTING
        config.insert(loaded)
        assertEquals("conditional write", true, config.insertIfUnchanged(loaded, IdentityRecordKey.of(loaded)))

        assertNoDecode()
        assertEquals(hex, config.storedHex())
        assertEquals(UsernameRequestStatus.VOTING, config.load()?.usernameRequested)
    }

    @Test
    fun `an unread record saved after another writer changed or removed the blob writes the loaded one`() = runBlocking {
        val config = config()
        val hex = config.store(identity(4))
        val loaded = requireNotNull(config.load())

        config.writeHex(identity(5).toBuffer().toHex())
        config.insert(loaded)
        assertEquals(hex, config.storedHex())

        config.writeHex(null)
        config.insert(loaded)
        assertEquals(hex, config.storedHex())
        assertNoDecode()
    }

    /**
     * Today a save re-encodes the decoded identity; now an unread record writes the loaded blob
     * back. The bytes can differ (the legacy decode drops the revision, so today's re-encode
     * writes 0), but both blobs decode to the same identity, and only that decode reads the blob.
     */
    @Test
    fun `the written-back blob decodes to the same identity as today's re-encode`() = runBlocking {
        val config = config()
        for (original in listOf(identity(6), identity(7, bounded = true))) {
            val hex = config.store(original)
            val decoded = requireNotNull(requireNotNull(config.load()).identity)
            val reencoded = serializeIdentityToleratingContractBounds(decoded).toHex()
            assertEquals("revision lost by the legacy decode", 0, decoded.revision)
            assertNotEquals("so today's re-encode rewrote the bytes", hex, reencoded)

            config.writeHex(reencoded)
            val fromReencoded = requireNotNull(requireNotNull(config.load()).identity)
            assertEquals(reencoded, serializeIdentityToleratingContractBounds(fromReencoded).toHex())
        }
    }

    @Test
    fun `an assigned identity is serialized as before, tolerating contract bounds`() = runBlocking {
        val config = config()
        config.store(identity(8))
        val loaded = requireNotNull(config.load())

        val replacement = identity(9, bounded = true)
        loaded.identity = replacement
        config.insert(loaded)

        assertEquals(serializeIdentityToleratingContractBounds(replacement).toHex(), config.storedHex())
        assertNoDecode()
    }

    @Test
    fun `assigning null leaves the stored blob as the store has it, as before`() = runBlocking {
        val config = config()
        config.store(identity(10))
        val loaded = requireNotNull(config.load())
        val other = identity(11).toBuffer().toHex()
        config.writeHex(other)

        loaded.identity = null
        config.insert(loaded)

        assertNull(loaded.identity)
        assertEquals(other, config.storedHex())
        assertNoDecode()
    }

    @Test
    fun `a read identity is serialized from the decoded value, so in-place changes persist`() = runBlocking {
        val config = config()
        config.store(identity(12))
        val loaded = requireNotNull(config.load())

        val decoded = requireNotNull(loaded.identity)
        decoded.balance = 4242L
        config.insert(loaded)

        assertEquals(serializeIdentityToleratingContractBounds(decoded).toHex(), config.storedHex())
        assertEquals(4242L, config.load()?.identity?.balance)
    }

    @Test
    fun `a corrupt blob loads, throws to the reader like the legacy decode, and saves unchanged`() = runBlocking {
        val config = config()
        config.store(identity(13))
        val corrupt = "ff00ab"
        config.writeHex(corrupt)

        val legacy = runCatching { factory.createFromBuffer(Converters.fromHex(corrupt)) }
            .exceptionOrNull()
        assertNotNull("the legacy decode rejects the blob", legacy)

        val loaded = requireNotNull(config.load())
        val thrown = assertThrows(Throwable::class.java) { loaded.identity }
        assertEquals(legacy?.javaClass, thrown.javaClass)
        // still fail-closed on the next read: a failed decode never turns into null
        assertEquals(legacy?.javaClass, assertThrows(Throwable::class.java) { loaded.identity }.javaClass)

        val unread = requireNotNull(config.load())
        unread.creationStateErrorMessage = "error"
        config.insert(unread)
        assertEquals(corrupt, config.storedHex())
        config.insert(loaded)
        assertEquals(corrupt, config.storedHex())
    }

    @Test
    fun `the recovery seed is the loaded record and carries the undecoded blob to its save`() = runBlocking {
        val config = config()
        val hex = config.store(identity(14))
        config.recordRequestedNames(RequestedNameContext(userId, "alice2", null))

        val seed = requireNotNull(config.loadRecoverySeed(userId, fromCreation = false))
        config.writeHex(null)
        config.insert(seed)

        assertEquals("alice2", config.load()?.username)
        assertEquals(hex, config.storedHex())
        assertNoDecode()
    }
}
