/*
 * Copyright 2019 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui

import android.content.Intent
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.security.SecurityFunctions
import de.schildbach.wallet.security.SecurityGuard
import de.schildbach.wallet.service.WalletFactory
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.util.MnemonicCodeExt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.crypto.HDKeyDerivation
import org.bitcoinj.crypto.MnemonicException
import org.bitcoinj.wallet.DeterministicSeed
import org.dash.wallet.common.Configuration
import org.slf4j.LoggerFactory
import java.util.*
import javax.inject.Inject

data class RecoveryData(val pin: String?, val requiresReset: Boolean)

@HiltViewModel
class RestoreWalletFromSeedViewModel @Inject constructor(
    private val walletApplication: WalletApplication,
    private val walletFactory: WalletFactory,
    private val configuration: Configuration,
    private val securityFunctions: SecurityFunctions,
    private val dashPayConfig: DashPayConfig
) : ViewModel() {

    private val log = LoggerFactory.getLogger(RestoreWalletFromSeedViewModel::class.java)

    private val securityGuard = SecurityGuard.getInstance()

    val selectedCreationDate = MutableLiveData<Long?>() // timestamp in seconds, null = not selected

    /**
     * Verify that the provided mnemonic matches the wallet by comparing derived keys
     * This works even when encryption is completely broken
     */
    private fun verifyMnemonicMatchesWallet(words: List<String>): Boolean {
        try {
            val wallet = walletApplication.wallet ?: return false

            // Create a deterministic seed from the provided mnemonic
            val providedSeed = DeterministicSeed(words, null, "", 0L)

            // Derive the master key from the provided mnemonic
            val providedMasterKey = HDKeyDerivation.createMasterPrivateKey(providedSeed.seedBytes)

            // Derive a key at m/44'/5'/0'/0/0 (first receiving address in BIP44 path for Dash)
            // This is a standard path that should exist in any Dash wallet
            val derivationPath = listOf(
                ChildNumber(44, true),  // BIP44
                ChildNumber(5, true),   // Dash coin type
                ChildNumber(0, true),   // Account 0
                ChildNumber(0, false),  // External chain (receiving)
                ChildNumber(0, false)   // First address
            )

            var providedKey: DeterministicKey = providedMasterKey
            for (childNumber in derivationPath) {
                providedKey = HDKeyDerivation.deriveChildKey(providedKey, childNumber)
            }

            // Get the same key from the wallet's key chain
            val walletKeyChain = wallet.activeKeyChain
            if (walletKeyChain != null) {
                // Get the key at the same path from the wallet
                val walletKey = walletKeyChain.getKeyByPath(derivationPath, false)

                if (walletKey != null) {
                    // Compare the public keys (more reliable than comparing seeds directly)
                    val match = providedKey.pubKey.contentEquals(walletKey.pubKey)
                    log.info("Mnemonic verification: public keys match = $match")
                    return match
                } else {
                    log.warn("Could not get key from wallet at derivation path")
                }
            } else {
                log.warn("Wallet active key chain is null")
            }
        } catch (e: Exception) {
            log.error("Error verifying mnemonic against wallet: ${e.message}", e)
        }
        return false
    }

    private suspend fun recover(words: List<String>): RecoveryData? = withContext(Dispatchers.Default) {
        try {
            // Try primary encryption system (KeyStore-based)
            val password = securityGuard.retrievePassword()
            val decryptedSeed = securityFunctions.decryptSeed(password)
            val seed = decryptedSeed.mnemonicCode!!.toTypedArray()

            if (seed contentEquals words.toTypedArray()) {
                return@withContext RecoveryData(securityGuard.retrievePin(), false)
            }
        } catch (primaryException: Exception) {
            log.warn("Primary encryption failed during recovery: ${primaryException.message}")

            // Primary failed - try mnemonic-based fallback recovery
            // User has provided their recovery phrase, so we can use it to recover everything!
            try {
                log.info("Attempting mnemonic-based fallback recovery")

                // Recover both PIN and wallet password using the mnemonic
                val recoveredPin = securityGuard.recoverPinWithMnemonic(words)
                val recoveredPassword = securityGuard.recoverPasswordWithMnemonic(words)

                log.info("Mnemonic-based fallback recovery succeeded")

                // Verify the recovered data matches the provided mnemonic
                val decryptedSeed = securityFunctions.decryptSeed(recoveredPassword)
                val seed = decryptedSeed.mnemonicCode!!.toTypedArray()

                if (seed contentEquals words.toTypedArray()) {
                    // Success! Self-healing has already occurred in the recovery methods
                    log.info("Recovered PIN matches provided mnemonic, system healed")
                    return@withContext RecoveryData(recoveredPin, false)
                } else {
                    log.warn("Recovered seed doesn't match provided mnemonic")
                }
            } catch (fallbackException: Exception) {
                log.error("Mnemonic-based fallback recovery also failed: ${fallbackException.message}", fallbackException)

                // Both primary and mnemonic-based fallback failed
                // Last resort: verify mnemonic matches wallet by comparing derived keys
                // This works even when encryption is completely broken
                log.info("Attempting cryptographic verification of mnemonic against wallet")
                if (verifyMnemonicMatchesWallet(words)) {
                    log.info("Mnemonic cryptographically verified against wallet!")
                    // The mnemonic is correct, but we cannot recover the PIN
                    // User will need to set a new PIN
                    // Return empty string to signal "mnemonic verified but PIN not recovered"
                    // TODO: we will need to do a wallet reset
                    // TODO:   recreate wallet, reset blockchain
                    return@withContext RecoveryData("", true)
                } else {
                    log.warn("Mnemonic verification failed - does not match wallet")
                }
            }
        }

        return@withContext null
    }

    fun setWalletCreationDate(timeInMillis: Long?) {
        selectedCreationDate.value = timeInMillis?.let { it / 1000 } // convert to seconds
    }

    fun clearWalletCreationDate() {
        selectedCreationDate.value = null
    }

    /**
     * Normalize - converts all letter to lowercase and to words matching those of a BIP39 word list.
     * Examples:
     *   Satoshi -> satoshi (all letters become lowercase)
     *   TODO: also handle this: medaille -> médaille
     * @param words - the recovery phrase word list
     */
    private fun normalize(words: List<String>): List<String> {
        return words.map { it.lowercase(Locale.getDefault()) }
    }

    suspend fun restoreWalletFromSeed(words: List<String>): Boolean = withContext(Dispatchers.IO) {
        if (isSeedValid(words)) {
            val creationTime = selectedCreationDate.value
            val wallet = walletFactory.restoreFromSeed(Constants.NETWORK_PARAMETERS, normalize(words), creationTime)
            walletApplication.setWallet(wallet)
            log.info("successfully restored wallet from seed")
            configuration.disarmBackupSeedReminder()
            configuration.isRestoringBackup = true
            viewModelScope.launch { dashPayConfig.disableNotifications() }
            walletApplication.resetBlockchainState()
            true
        } else {
            false
        }
    }

    suspend fun recoverPin(words: List<String>): RecoveryData? {
        return if (isSeedValid(words)) {
            recover(normalize(words))
        } else {
            null
        }
    }

    /**
     * Checks to see if this seed is valid.  The validation is not case sensitive, nor does it
     * depend on accent marks or other diacritics.
     *
     * @param words
     * @return
     */
    private fun isSeedValid(words: List<String>): Boolean {
        return try {
            MnemonicCodeExt.getInstance().check(walletApplication, words)
            true
        } catch (x: MnemonicException) {
            log.info("problem restoring wallet from seed: ", x)
            false
        }
    }
}
