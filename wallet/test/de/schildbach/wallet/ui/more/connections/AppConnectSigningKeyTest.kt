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

package de.schildbach.wallet.ui.more.connections

import android.app.Application
import de.schildbach.wallet.service.platform.IdentityKeyChainBackfillException
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.service.platform.PlatformService
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.testPlatformRepo
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bitcoinj.core.Context
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.EncryptableItem
import org.bitcoinj.crypto.IDeterministicKey
import org.bitcoinj.crypto.KeyCrypterScrypt
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.Script
import org.bitcoinj.wallet.AuthenticationKeyChain
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.WalletEx
import org.bitcoinj.wallet.WalletProtobufSerializer
import org.bitcoinj.wallet.authentication.AuthenticationGroupExtension
import org.bouncycastle.crypto.params.KeyParameter
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.dpp.identity.Identity
import org.dashj.platform.dpp.identity.IdentityPublicKey
import org.dashj.platform.sdk.KeyType
import org.dashj.platform.sdk.Purpose
import org.dashj.platform.sdk.SecurityLevel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.EnumSet

/**
 * DashConnect key lookups against a REAL dashj identity chain, in the state an identity created
 * by the Kotlin SDK reaches dashj in: its canonical keys (keyId == derivation index on the
 * identity-0 chain) are registered on Platform but none is issued on the chain, so
 * `findKeyFromPubKey` finds nothing until [AppConnectSession.readSigningIdentity] backfills them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    application = Application::class, sdk = [29], manifest = Config.NONE,
    shadows = [AppConnectKeyStateTest.ShadowSdkEnums::class]
)
class AppConnectSigningKeyTest {
    private val params = TestNet3Params.get()
    private val time = 1_790_000_000_000L
    private val identityId = Identifier.from(ByteArray(32) { 7 })

    private fun seed(fill: Byte) = DeterministicSeed(ByteArray(16) { fill }, "", 0L)

    /** This wallet's identity chain, created as the wallet creates it: nothing issued. */
    private val walletAuth = AuthenticationGroupExtension(params).apply {
        addKeyChains(params, seed(1), EnumSet.of(AuthenticationKeyChain.KeyChainType.BLOCKCHAIN_IDENTITY))
    }
    private val chain = walletAuth.identityKeyChain

    private fun chainPublicKey(auth: AuthenticationGroupExtension, index: Int): ByteArray =
        auth.identityKeyChain.watchingKey.deriveChildKey(ChildNumber(index, true)).pubKey

    /** The Kotlin SDK's canonical registration set: 0 MASTER, 1 CRITICAL, 2 HIGH, 3 TRANSFER. */
    private fun sdkIdentity(auth: AuthenticationGroupExtension, extra: List<IdentityPublicKey> = emptyList()) = Identity(
        identityId,
        listOf(
            key(0, Purpose.AUTHENTICATION, SecurityLevel.MASTER, chainPublicKey(auth, 0)),
            key(1, Purpose.AUTHENTICATION, SecurityLevel.CRITICAL, chainPublicKey(auth, 1)),
            key(2, Purpose.AUTHENTICATION, SecurityLevel.HIGH, chainPublicKey(auth, 2)),
            key(3, Purpose.TRANSFER, SecurityLevel.CRITICAL, chainPublicKey(auth, 3))
        ) + extra,
        1_000_000L, 1, 1
    )

    private fun key(id: Int, purpose: Purpose, level: SecurityLevel, data: ByteArray) =
        IdentityPublicKey(id, KeyType.ECDSA_SECP256K1, purpose, level, null, data, false)

    private fun platformRepo(
        auth: AuthenticationGroupExtension? = walletAuth,
        encrypted: Boolean = false
    ): PlatformRepo {
        val wallet = mockk<WalletEx>(relaxed = true)
        every { wallet.isEncrypted } returns encrypted
        val walletRepo = testPlatformRepo()
        every { walletRepo.walletApplication.wallet } returns wallet
        every { walletRepo.walletApplication.authenticationGroupExtension } returns auth
        return walletRepo
    }

    private fun session(identity: Identity, walletRepo: PlatformRepo = platformRepo()): AppConnectSession {
        val service = mockk<PlatformService>(relaxed = true)
        every { service.platform.client.getIdentity(any(), true) } returns identity
        val identities = mockk<IdentityRepository>(relaxed = true)
        every { identities.blockchainIdentity?.uniqueIdentifier } returns identityId
        return AppConnectSession(service, walletRepo, identities, mockk(relaxed = true))
    }

    @Test fun `SDK-created identity keys are unusable until the signing read issues them`() {
        val identity = sdkIdentity(walletAuth)
        val session = session(identity)
        // The post-SDK-creation state: registered on Platform, unknown to the dashj chain. A new
        // chain holds only its index-0 and index-1 keys, so MASTER (keyId 0) and the SDK's
        // CRITICAL key (keyId 1) happen to resolve already; HIGH (2) and TRANSFER (3) do not.
        assertThrows(IllegalStateException::class.java) { session.signingKey(identity, SecurityLevel.HIGH, time) }
        (2..3).forEach { keyId -> assertNull(chain.findKeyFromPubKey(identity.publicKeys[keyId].data)) }

        assertSame(identity, session.readSigningIdentity(null))

        assertEquals(0, session.signingKey(identity, SecurityLevel.MASTER, time).id)
        assertEquals(1, session.signingKey(identity, SecurityLevel.CRITICAL, time).id)
        assertEquals(2, session.signingKey(identity, SecurityLevel.HIGH, time).id)
        // the token-purchase signer and WalletSignerCallback resolve the private key the same way
        identity.publicKeys.forEach { assertNotNull(chain.findKeyFromPubKey(it.data)) }
    }

    @Test fun `keys from another seed stay unissued and every lookup still fails closed`() {
        val foreignAuth = AuthenticationGroupExtension(params).apply {
            addKeyChains(params, seed(2), EnumSet.of(AuthenticationKeyChain.KeyChainType.BLOCKCHAIN_IDENTITY))
        }
        val identity = sdkIdentity(foreignAuth)
        val session = session(identity)

        session.readSigningIdentity(null)

        listOf(SecurityLevel.MASTER, SecurityLevel.CRITICAL, SecurityLevel.HIGH).forEach { level ->
            val error = assertThrows(IllegalStateException::class.java) { session.signingKey(identity, level, time) }
            assertTrue(error.message!!.contains("Wallet-controlled unbound"))
        }
        identity.publicKeys.forEach { assertNull(chain.findKeyFromPubKey(it.data)) }
    }

    @Test fun `app keys at later key IDs are not mistaken for identity chain keys`() {
        // DashConnect app keys take the next key IDs but derive from DIP-13 sub-features 6/7,
        // never from the identity-0 chain, so their IDs must not cause chain indexes to be issued.
        val appKey = IdentityPublicKey(4, KeyType.ECDSA_HASH160, Purpose.AUTHENTICATION, SecurityLevel.CRITICAL,
            null, ByteArray(20) { 9 }, false)
        val identity = sdkIdentity(walletAuth, listOf(appKey))
        val session = session(identity)

        session.readSigningIdentity(null)

        assertEquals(1, session.signingKey(identity, SecurityLevel.CRITICAL, time).id)
        assertNull(chain.findKeyFromPubKey(chainPublicKey(walletAuth, 4)))
    }

    @Test fun `a backfill that cannot complete fails the signing read before any signing`() {
        val identity = sdkIdentity(walletAuth)
        // no authentication extension: nothing can be issued, so signing would later find no key
        val session = session(identity, platformRepo(auth = null))

        val error = assertThrows(IllegalStateException::class.java) { session.readSigningIdentity(null) }

        assertEquals("Unable to read this wallet's identity keys. Try again.", error.message)
        assertTrue(error.cause is IdentityKeyChainBackfillException)
    }

    @Test fun `strict backfill fails on an encrypted wallet without its key, lenient returns 0`() {
        val identity = sdkIdentity(walletAuth)
        val walletRepo = platformRepo(encrypted = true)

        assertThrows(IdentityKeyChainBackfillException::class.java) {
            walletRepo.requireIdentityChainKeys(identity, null)
        }
        assertEquals(0, walletRepo.ensureIdentityChainKeys(identity, null))
        identity.publicKeys.drop(2).forEach { assertNull(chain.findKeyFromPubKey(it.data)) }
    }

    @Test fun `strict backfill on a fully issued chain needs no key on an encrypted wallet`() {
        val identity = sdkIdentity(walletAuth)
        assertEquals(2, platformRepo().requireIdentityChainKeys(identity, null))

        // every key resolves on the chain, so nothing is derived and the chain is never decrypted
        assertEquals(0, platformRepo(encrypted = true).requireIdentityChainKeys(identity, null))
    }

    @Test fun `strict backfill fails on a derivation error but still skips keys that are not ours`() {
        // index 2 cannot be derived; keyId 1 carries a foreign key (mismatch, not an error)
        val realParent = chain.watchingKey
        val parent = mockk<IDeterministicKey>()
        every { parent.deriveChildKey(any<ChildNumber>()) } answers {
            val child = firstArg<ChildNumber>()
            if (child.num() == 2) throw IllegalArgumentException("cannot derive") else realParent.deriveChildKey(child)
        }
        val mockChain = mockk<AuthenticationKeyChain>(relaxed = true)
        every { mockChain.watchingKey } returns parent
        every { mockChain.findKeyFromPubKey(any()) } returns null
        val auth = mockk<AuthenticationGroupExtension>(relaxed = true)
        every { auth.getKeyChain(AuthenticationKeyChain.KeyChainType.BLOCKCHAIN_IDENTITY) } returns mockChain
        val foreign = ECKey().pubKey
        val identity = Identity(
            identityId,
            listOf(
                key(0, Purpose.AUTHENTICATION, SecurityLevel.MASTER, chainPublicKey(walletAuth, 0)),
                key(1, Purpose.AUTHENTICATION, SecurityLevel.CRITICAL, foreign),
                key(2, Purpose.AUTHENTICATION, SecurityLevel.HIGH, chainPublicKey(walletAuth, 2)),
                key(3, Purpose.TRANSFER, SecurityLevel.CRITICAL, chainPublicKey(walletAuth, 3))
            ),
            1_000_000L, 1, 1
        )
        val walletRepo = platformRepo(auth = auth)

        val error = assertThrows(IdentityKeyChainBackfillException::class.java) {
            walletRepo.requireIdentityChainKeys(identity, null)
        }
        assertTrue(error.cause is IllegalArgumentException)
        verify(exactly = 0) { auth.addNewKey(any(), any()) }

        // the lenient variant (sync/broadcast callers) skips the underivable key and issues 0 and 3
        assertEquals(2, walletRepo.ensureIdentityChainKeys(identity, null))
        verify(exactly = 2) { auth.addNewKey(AuthenticationKeyChain.KeyChainType.BLOCKCHAIN_IDENTITY, any()) }

        // without the derivation error, the foreign keyId 1 is skipped and the strict variant succeeds
        every { parent.deriveChildKey(any<ChildNumber>()) } answers {
            realParent.deriveChildKey(firstArg<ChildNumber>())
        }
        assertEquals(3, walletRepo.requireIdentityChainKeys(identity, null))
    }

    // --- a genuinely encrypted wallet (no mocked isEncrypted) ---------------------------------

    /** Low scrypt cost: production parameters only slow the test; the crypto path is identical. */
    private val keyCrypter = KeyCrypterScrypt(1024)
    private val aesKey: KeyParameter by lazy { keyCrypter.deriveKey("correct horse") }

    /**
     * A real wallet on the same seed as [walletAuth], with its identity chain created and then
     * encrypted the way the app does it: `Wallet.encrypt` re-encrypts every key chain extension.
     */
    private fun encryptedWallet(): Pair<Wallet, AuthenticationGroupExtension> {
        Context.propagate(Context.getOrCreate(params))
        val wallet = WalletEx.fromSeed(params, seed(1), Script.ScriptType.P2PKH)
        val auth = AuthenticationGroupExtension(wallet)
        wallet.addExtension(auth)
        auth.addKeyChains(params, seed(1), EnumSet.of(AuthenticationKeyChain.KeyChainType.BLOCKCHAIN_IDENTITY))
        wallet.encrypt(keyCrypter, aesKey)
        assertTrue(wallet.isEncrypted)
        assertTrue(auth.isEncrypted)
        assertNotNull(auth.identityKeyChain.keyCrypter)
        return wallet to auth
    }

    /** The app's reload: write the wallet file and read it back with fresh extension objects (WalletFactory). */
    private fun reload(wallet: Wallet): Pair<Wallet, AuthenticationGroupExtension> {
        val bytes = ByteArrayOutputStream().also { WalletProtobufSerializer().writeWallet(wallet, it) }.toByteArray()
        val reloaded = WalletProtobufSerializer().readWallet(
            ByteArrayInputStream(bytes),
            false,
            arrayOf(AuthenticationGroupExtension(params))
        )
        val auth = reloaded.getKeyChainExtension(AuthenticationGroupExtension.EXTENSION_ID) as? AuthenticationGroupExtension
        return reloaded to checkNotNull(auth) { "reloaded wallet lost its authentication extension" }
    }

    private fun platformRepo(wallet: Wallet, auth: AuthenticationGroupExtension): PlatformRepo {
        val walletRepo = testPlatformRepo()
        every { walletRepo.walletApplication.wallet } returns wallet
        every { walletRepo.walletApplication.authenticationGroupExtension } returns auth
        return walletRepo
    }

    /** The key a signer resolves for [identityKey], checked to be encrypted and to sign as that key. */
    private fun assertEncryptedSigningKey(auth: AuthenticationGroupExtension, identityKey: IdentityPublicKey, index: Int) {
        val walletKey = checkNotNull(auth.identityKeyChain.findKeyFromPubKey(identityKey.data) as? IDeterministicKey) {
            "key $index is not issued on the identity chain"
        }
        val encryptable = checkNotNull(walletKey as? EncryptableItem) { "key $index is not encryptable" }
        assertTrue("key $index must stay encrypted", encryptable.isEncrypted)
        assertNotNull(encryptable.encryptedData)
        assertThrows(Exception::class.java) { walletKey.privKeyBytes }
        // decrypts with the wallet's key to exactly this wallet's private key at that chain index
        val plain = walletAuth.identityKeyChain.watchingKey.deriveChildKey(ChildNumber(index, true))
        assertArrayEquals(plain.privKeyBytes, walletKey.decrypt(keyCrypter, aesKey).privKeyBytes)
        // and signs the way WalletSignerCallback / the purchase signer do (signHash with the key)
        val hash = Sha256Hash.twiceOf("dashconnect $index".toByteArray())
        val signature = walletKey.signHash(hash, aesKey)
        assertArrayEquals(identityKey.data, ECKey.signedMessageToKey(hash, signature).pubKey)
    }

    @Test fun `encrypted wallet backfill issues encrypted keys that sign after a reload and are not reissued`() {
        val (wallet, auth) = encryptedWallet()
        val identity = sdkIdentity(walletAuth)
        val chainBefore = auth.identityKeyChain
        // the SDK-created state: HIGH (2) and TRANSFER (3) are registered but unknown to the chain
        (2..3).forEach { assertNull(chainBefore.findKeyFromPubKey(identity.publicKeys[it].data)) }
        val keysBefore = chainBefore.numKeys()

        // the signing read: decrypt the parent, derive 2 and 3, re-encrypt them and import them
        val session = session(identity, platformRepo(wallet, auth))
        assertSame(identity, session.readSigningIdentity(aesKey))
        assertEquals(keysBefore + 2, auth.identityKeyChain.numKeys())
        (0..3).forEach { assertEncryptedSigningKey(auth, identity.publicKeys[it], it) }

        // reload from the serialized wallet, as the next app launch does
        val (reloaded, reloadedAuth) = reload(wallet)
        assertTrue(reloaded.isEncrypted)
        assertTrue(reloadedAuth.isEncrypted)
        assertNotNull(reloadedAuth.identityKeyChain.keyCrypter)
        assertEquals(keysBefore + 2, reloadedAuth.identityKeyChain.numKeys())
        (0..3).forEach { assertEncryptedSigningKey(reloadedAuth, identity.publicKeys[it], it) }
        val reloadedRepo = platformRepo(reloaded, reloadedAuth)
        val reloadedSession = session(identity, reloadedRepo)
        assertEquals(2, reloadedSession.signingKey(identity, SecurityLevel.HIGH, time).id)
        assertEquals(1, reloadedSession.signingKey(identity, SecurityLevel.CRITICAL, time).id)

        // retry after the reload: everything resolves, nothing is imported again (strict and lenient)
        assertSame(identity, reloadedSession.readSigningIdentity(aesKey))
        assertEquals(0, reloadedRepo.requireIdentityChainKeys(identity, aesKey))
        assertEquals(0, reloadedRepo.ensureIdentityChainKeys(identity, aesKey))
        // and a fully issued encrypted chain needs no key at all
        assertEquals(0, reloadedRepo.requireIdentityChainKeys(identity, null))
        assertEquals(keysBefore + 2, reloadedAuth.identityKeyChain.numKeys())
    }

    @Test fun `encrypted wallet backfill with the wrong key imports nothing`() {
        val (wallet, auth) = encryptedWallet()
        val identity = sdkIdentity(walletAuth)
        val walletRepo = platformRepo(wallet, auth)
        val keysBefore = auth.identityKeyChain.numKeys()

        assertThrows(IdentityKeyChainBackfillException::class.java) {
            walletRepo.requireIdentityChainKeys(identity, keyCrypter.deriveKey("wrong password"))
        }
        assertEquals(keysBefore, auth.identityKeyChain.numKeys())
        (2..3).forEach { assertNull(auth.identityKeyChain.findKeyFromPubKey(identity.publicKeys[it].data)) }
        assertTrue(wallet.isEncrypted)
    }
}
