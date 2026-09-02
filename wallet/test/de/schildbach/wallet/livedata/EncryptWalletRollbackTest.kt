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

package de.schildbach.wallet.livedata

import org.bitcoinj.core.Context
import org.bitcoinj.crypto.KeyCrypterScrypt
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.Script
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.Wallet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Host-JVM coverage of [EncryptWalletLiveData.encryptAndSavePassword]: onboarding
 * saves the spending password only AFTER `wallet.encrypt(...)`, so a failure in
 * between would otherwise strand the wallet encrypted with a password nobody
 * stored — unspendable, and un-retryable because a second attempt refuses to
 * encrypt an already-encrypted wallet.
 */
class EncryptWalletRollbackTest {

    private val params = TestNet3Params.get()

    // Fixture phrase already used by other tests in this repo — carries no funds.
    private val mnemonic =
        "weapon elder job emotion aunt include deer owner salon census half divide"

    // Low iteration count: production scrypt targets would make this test
    // take seconds; the encryption/rollback path under test is identical.
    private val keyCrypter = KeyCrypterScrypt(1024)

    @Before
    fun propagateDashjContext() {
        Context.propagate(Context.getOrCreate(params))
    }

    private fun newWallet(): Wallet =
        Wallet.fromSeed(
            params,
            DeterministicSeed(mnemonic, null, "", System.currentTimeMillis() / 1000),
            Script.ScriptType.P2PKH
        )

    @Test
    fun savePasswordSucceeds_walletEndsEncrypted() {
        val wallet = newWallet()
        var savedPassword: String? = null

        EncryptWalletLiveData.encryptAndSavePassword(wallet, keyCrypter, "correct horse") {
            savedPassword = it
        }

        assertTrue(wallet.isEncrypted)
        assertEquals("correct horse", savedPassword)
    }

    @Test
    fun savePasswordFails_encryptionIsRolledBackAndRetryWorks() {
        val wallet = newWallet()

        val thrown = assertThrows(IOException::class.java) {
            EncryptWalletLiveData.encryptAndSavePassword(wallet, keyCrypter, "correct horse") {
                throw IOException("keystore write failed")
            }
        }
        assertEquals("keystore write failed", thrown.message)

        // the wallet must be back in its pre-call state, or the retry below could
        // never re-encrypt and the stored-password/wallet-key pair would stay split
        assertFalse(wallet.isEncrypted)

        var savedPassword: String? = null
        EncryptWalletLiveData.encryptAndSavePassword(wallet, keyCrypter, "correct horse") {
            savedPassword = it
        }
        assertTrue(wallet.isEncrypted)
        assertEquals("correct horse", savedPassword)
    }
}
