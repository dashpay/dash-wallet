/*
 * Copyright 2022 Dash Core Group.
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

package de.schildbach.wallet.security

import androidx.fragment.app.FragmentActivity
import com.google.common.base.Preconditions
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.payments.SendCoinsTaskRunner
import de.schildbach.wallet.ui.CheckPinDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Address
import org.bitcoinj.crypto.KeyCrypterException
import org.bitcoinj.crypto.KeyCrypterScrypt
import org.bitcoinj.wallet.Wallet
import org.bouncycastle.crypto.params.KeyParameter
import org.dash.wallet.common.services.ISecurityFunctions
import org.slf4j.LoggerFactory
import javax.inject.Inject

class SecurityFunctions @Inject constructor(
    private val walletApplication: WalletApplication
): ISecurityFunctions {
    private val log = LoggerFactory.getLogger(SendCoinsTaskRunner::class.java)

    override suspend fun requestPinCode(activity: FragmentActivity): String? {
        return CheckPinDialog.showAsync(activity)
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    override suspend fun signMessage(address: Address, message: String): String {
        val securityGuard = SecurityGuard()
        val password = securityGuard.retrievePassword()
        val keyParameter = deriveKey(walletApplication.wallet!!, password, walletApplication.scryptIterationsTarget())
        val key = walletApplication.wallet?.findKeyFromAddress(address)
        return key?.signMessage(message, keyParameter) ?: ""
    }

    @Throws(KeyCrypterException::class)
    fun deriveKey(
        wallet: Wallet,
        password: String,
        scryptIterationsTarget: Int
    ): KeyParameter {
        require(wallet.isEncrypted)
        val keyCrypter = wallet.keyCrypter!!

        // Key derivation takes time.
        var key = keyCrypter.deriveKey(password)

        // If the key isn't derived using the desired parameters, derive a new key.
        if (keyCrypter is KeyCrypterScrypt) {
            val scryptIterations = keyCrypter.scryptParameters.n

            if (scryptIterations != scryptIterationsTarget.toLong()) {
                log.info("upgrading scrypt iterations from {} to {}; re-encrypting wallet",
                    scryptIterations, scryptIterationsTarget)
                val newKeyCrypter = KeyCrypterScrypt(scryptIterationsTarget)
                val newKey: KeyParameter = newKeyCrypter.deriveKey(password)

                // Re-encrypt wallet with new key.
                try {
                    wallet.changeEncryptionKey(newKeyCrypter, key, newKey)
                    key = newKey
                    log.info("scrypt upgrade succeeded")
                } catch (x: KeyCrypterException) {
                    log.info("scrypt upgrade failed: {}", x.message)
                }
            }
        }

        // Hand back the (possibly changed) encryption key.
        return key
    }
}