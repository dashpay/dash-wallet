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

import de.schildbach.wallet.WalletApplication.WalletInstallResult
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
import org.dash.wallet.common.data.BlockchainServiceConfig
import org.slf4j.LoggerFactory
import java.util.*
import javax.inject.Inject

data class RecoveryData(val pin: String?, val requiresReset: Boolean)

enum class SeedRestoreResult {
    RESTORED,
    INVALID_SEED,

    /** The app refused the replacement (see WalletApplication.isWalletReplacementRefused); nothing was written. */
    REFUSED,

    /** Another onboarding screen installed a wallet first; nothing was written. */
    WALLET_ALREADY_INSTALLED
}

@HiltViewModel
class RestoreWalletFromSeedViewModel @Inject constructor(
    private val walletApplication: WalletApplication,
    private val walletFactory: WalletFactory,
    private val configuration: Configuration,
    private val securityFunctions: SecurityFunctions,
    private val dashPayConfig: DashPayConfig,
    private val blockchainServiceConfig: BlockchainServiceConfig
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

    suspend fun restoreWalletFromSeed(
        words: List<String>,
        forgotPinRecovery: Boolean = false
    ): SeedRestoreResult = withContext(Dispatchers.IO) {
        // Forgot-PIN recovery replaces the installed wallet with itself, so it rebuilds from the
        // spelling that wallet holds: any other spelling is a different wallet.
        val restoreWords = if (forgotPinRecovery) {
            spellings(words).firstOrNull(::verifyMnemonicMatchesWallet) ?: canonicalWordsOrNull(words)
        } else {
            canonicalWordsOrNull(words)
        }
        if (restoreWords != null) {
            // Before any restore work: a wipe marker refuses the replacement.
            if (walletApplication.isWalletReplacementRefused) {
                log.warn("seed restore refused before it began: a wallet reset is unfinished or unverified")
                return@withContext SeedRestoreResult.REFUSED
            }
            val creationTime = selectedCreationDate.value
            val wallet = walletFactory.restoreFromSeed(Constants.NETWORK_PARAMETERS, restoreWords)
            // Forgot-PIN recovery replaces the installed wallet on purpose; an
            // onboarding restore must not (see WalletApplication.installWallet).
            when (walletApplication.installWallet(wallet, forgotPinRecovery)) {
                WalletInstallResult.ALREADY_INSTALLED -> {
                    log.warn("restored wallet dropped: another onboarding screen installed a wallet first")
                    return@withContext SeedRestoreResult.WALLET_ALREADY_INSTALLED
                }
                WalletInstallResult.REFUSED -> {
                    log.warn("restored wallet refused: a wallet reset is unfinished or unverified")
                    return@withContext SeedRestoreResult.REFUSED
                }
                WalletInstallResult.INSTALLED -> Unit
            }
            // Only once the replacement is accepted: a refused wallet must not
            // overwrite the existing wallet's persisted scan-start date.
            creationTime?.let { blockchainServiceConfig.setWalletCreationDate(it) }
            log.info("successfully restored wallet from seed")
            configuration.disarmBackupSeedReminder()
            configuration.isRestoringBackup = true
            viewModelScope.launch { dashPayConfig.disableNotifications() }
            walletApplication.resetBlockchainState()
            SeedRestoreResult.RESTORED
        } else {
            SeedRestoreResult.INVALID_SEED
        }
    }

    /**
     * Looks for the wallet's PIN with the recovery phrase the user typed, in each of the
     * [spellings] the wallet may hold. What authenticates the phrase is the wallet itself (its
     * decrypted seed, its mnemonic-based fallbacks or its keys), not a wordlist.
     */
    suspend fun recoverPin(words: List<String>): RecoveryData? {
        for (spelling in spellings(words)) {
            recover(spelling)?.let { return it }
        }
        return null
    }

    /**
     * The spellings an installed wallet may hold for the phrase the user typed: the wordlist's,
     * which a restore stores, then the words as typed (lowercased), which restores stored before
     * that — including a phrase no wordlist validates, which an earlier language search let through.
     */
    private fun spellings(words: List<String>): List<List<String>> {
        return listOfNotNull(canonicalWordsOrNull(words), words.map { it.lowercase(Locale.getDefault()) }).distinct()
    }

    /**
     * The words in the spelling of the BIP39 wordlist that validates them, or null when no
     * wordlist does. Validation is not case sensitive, nor does it depend on accent marks or other
     * diacritics, but the seed is derived from the text of the words: "medaille" for "médaille",
     * or an autocorrected "thème" for "theme", is a different wallet to dashj and no phrase at all
     * to the SDK. Everything built from the phrase takes the words this returns.
     */
    private fun canonicalWordsOrNull(words: List<String>): List<String>? {
        return try {
            MnemonicCodeExt.getInstance().canonicalize(walletApplication, words)
        } catch (x: MnemonicException) {
            log.info("problem restoring wallet from seed: ", x)
            null
        }
    }
}
