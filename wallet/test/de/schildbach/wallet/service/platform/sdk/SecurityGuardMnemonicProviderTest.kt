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

package de.schildbach.wallet.service.platform.sdk

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Context
import org.bitcoinj.crypto.KeyCrypterScrypt
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.Script
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.Wallet
import de.schildbach.wallet.data.WalletData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * Host-JVM (no native, no Android) coverage of the Phase 3b seed bridge:
 * [SecurityGuardMnemonicProvider] against real dashj wallets, exercising
 * the same `keyChainSeed.decrypt(keyCrypter, null, key)` sequence the
 * app's canonical seed flows (`SecurityFunctions.decryptSeed`,
 * `DecryptSeedTask`) run.
 */
class SecurityGuardMnemonicProviderTest {

    private val params = TestNet3Params.get()

    // Fixture phrase already used by other tests in this repo — carries no funds.
    private val mnemonic =
        "weapon elder job emotion aunt include deer owner salon census half divide"
    private val expectedWords = mnemonic.split(" ")

    // Low iteration count: production scrypt targets would make this test
    // take seconds; the derivation path under test is identical.
    private val keyCrypter = KeyCrypterScrypt(1024)
    private val encryptionKey = keyCrypter.deriveKey("1234")

    @Before
    fun propagateDashjContext() {
        Context.propagate(Context.getOrCreate(params))
    }

    private fun newSeed(): DeterministicSeed =
        DeterministicSeed(mnemonic, null, "", System.currentTimeMillis() / 1000)

    private fun newWallet(): Wallet =
        Wallet.fromSeed(params, newSeed(), Script.ScriptType.P2PKH)

    private fun providerFor(wallet: Wallet?): SecurityGuardMnemonicProvider {
        val walletData = mockk<WalletData>()
        every { walletData.wallet } returns wallet
        return SecurityGuardMnemonicProvider(walletData)
    }

    private fun reasonOf(block: suspend () -> Unit): MnemonicBridgeException.Reason =
        assertThrows(MnemonicBridgeException::class.java) {
            runBlocking { block() }
        }.reason

    // ── Encrypted wallet (the production path) ────────────────────────

    @Test
    fun encryptedWallet_withCorrectKey_returnsWords() {
        val wallet = newWallet().also { it.encrypt(keyCrypter, encryptionKey) }

        val words = runBlocking {
            providerFor(wallet).getMnemonicWords(WalletUnlock.EncryptionKey(encryptionKey))
        }

        assertEquals(expectedWords, words)
    }

    @Test
    fun encryptedWallet_withWrongKey_throwsBadEncryptionKey() {
        val wallet = newWallet().also { it.encrypt(keyCrypter, encryptionKey) }
        val wrongKey = keyCrypter.deriveKey("9999")

        val reason = reasonOf {
            providerFor(wallet).getMnemonicWords(WalletUnlock.EncryptionKey(wrongKey))
        }

        assertEquals(MnemonicBridgeException.Reason.BAD_ENCRYPTION_KEY, reason)
    }

    @Test
    fun encryptedWallet_withoutKey_throwsEncryptionKeyRequired() {
        val wallet = newWallet().also { it.encrypt(keyCrypter, encryptionKey) }

        val reason = reasonOf {
            providerFor(wallet).getMnemonicWords(WalletUnlock.Unencrypted)
        }

        assertEquals(MnemonicBridgeException.Reason.ENCRYPTION_KEY_REQUIRED, reason)
    }

    // ── Unencrypted (dev/test) wallets ────────────────────────────────

    @Test
    fun unencryptedWallet_withoutKey_returnsWords() {
        val words = runBlocking {
            providerFor(newWallet()).getMnemonicWords(WalletUnlock.Unencrypted)
        }

        assertEquals(expectedWords, words)
    }

    @Test
    fun unencryptedWallet_withUnnecessaryKey_isLenientAndReturnsWords() {
        val words = runBlocking {
            providerFor(newWallet()).getMnemonicWords(WalletUnlock.EncryptionKey(encryptionKey))
        }

        assertEquals(expectedWords, words)
    }

    // ── Pre-decrypted seed hand-off ───────────────────────────────────

    @Test
    fun decryptedSeedUnlock_returnsWordsWithoutTouchingWallet() {
        // No wallet at all: the seed carries everything.
        val provider = providerFor(wallet = null)

        val reasonWithoutSeed = reasonOf {
            provider.getMnemonicWords(WalletUnlock.Unencrypted)
        }
        assertEquals(MnemonicBridgeException.Reason.WALLET_UNAVAILABLE, reasonWithoutSeed)

        val words = runBlocking {
            provider.getMnemonicWords(WalletUnlock.DecryptedSeed(newSeed()))
        }
        assertEquals(expectedWords, words)
    }

    @Test
    fun decryptedSeedUnlock_withStillEncryptedSeed_throwsEncryptionKeyRequired() {
        val encryptedSeed = newSeed().encrypt(keyCrypter, encryptionKey)

        val reason = reasonOf {
            providerFor(wallet = null)
                .getMnemonicWords(WalletUnlock.DecryptedSeed(encryptedSeed))
        }

        assertEquals(MnemonicBridgeException.Reason.ENCRYPTION_KEY_REQUIRED, reason)
    }

    // ── Missing seed / missing words ──────────────────────────────────

    @Test
    fun watchOnlyWallet_throwsSeedMissing() {
        // dashj throws IKey.MissingPrivateKeyException from keyChainSeed on
        // a watching wallet; the provider must map it to the typed error.
        val watchingB58 = newWallet().watchingKey.serializePubB58(params)
        val watchOnly = Wallet.fromWatchingKeyB58(
            params, watchingB58, System.currentTimeMillis() / 1000
        )

        val reason = reasonOf {
            providerFor(watchOnly).getMnemonicWords(WalletUnlock.Unencrypted)
        }

        assertEquals(MnemonicBridgeException.Reason.SEED_MISSING, reason)
    }

    @Test
    fun missingWallet_throwsWalletUnavailable() {
        val reason = reasonOf {
            providerFor(wallet = null)
                .getMnemonicWords(WalletUnlock.EncryptionKey(encryptionKey))
        }

        assertEquals(MnemonicBridgeException.Reason.WALLET_UNAVAILABLE, reason)
    }

    @Test
    fun seedWithoutBip39Words_throwsMnemonicMissing() {
        // A raw-entropy seed (restored from seed bytes, no phrase): dashj
        // reports mnemonicCode == null.
        val wordlessSeed = mockk<DeterministicSeed> {
            every { isEncrypted } returns false
            every { mnemonicCode } returns null
        }

        val reason = reasonOf {
            providerFor(wallet = null)
                .getMnemonicWords(WalletUnlock.DecryptedSeed(wordlessSeed))
        }

        assertEquals(MnemonicBridgeException.Reason.MNEMONIC_MISSING, reason)
    }
}
