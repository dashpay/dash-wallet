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

            // Compare the account key at the installed chain's own account path: the path
            // differs by network (m/44'/5'/0' on mainnet, m/44'/1'/0' on testnet), and the
            // account key is always present, even in a wallet that has derived no address.
            val walletKeyChain = wallet.activeKeyChain
            if (walletKeyChain != null) {
                var providedKey: DeterministicKey = providedMasterKey
                for (childNumber in walletKeyChain.accountPath) {
                    providedKey = HDKeyDerivation.deriveChildKey(providedKey, childNumber)
                }
                // Compare the public keys (more reliable than comparing seeds directly)
                val match = providedKey.pubKey.contentEquals(walletKeyChain.watchingKey.pubKey)
                log.info("Mnemonic verification: public keys match = $match")
                return match
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
     * Restores the wallet from the recovery phrase the user typed.
     *
     * A new restore accepts only a phrase that is exactly valid in one wordlist and builds the
     * wallet from the wordlist's spelling of it; anything else throws, naming the reason.
     * Forgot-PIN recovery replaces the installed wallet with itself, so it rebuilds from the
     * spelling that wallet holds and refuses ([SeedRestoreResult.INVALID_SEED]) when the phrase
     * no longer matches the installed wallet in any spelling.
     *
     * @throws MnemonicException a new restore's phrase is not exactly valid
     */
    @Throws(MnemonicException::class)
    suspend fun restoreWalletFromSeed(
        words: List<String>,
        forgotPinRecovery: Boolean = false
    ): SeedRestoreResult = withContext(Dispatchers.IO) {
        val restoreWords = if (forgotPinRecovery) {
            spellings(words).firstOrNull(::verifyMnemonicMatchesWallet)
        } else {
            MnemonicCodeExt.getInstance().checkExact(walletApplication, words)
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
     * the phrase had to match a wordlist exactly — a missing accent, a different Unicode form, or
     * a phrase no wordlist validates.
     */
    private fun spellings(words: List<String>): List<List<String>> {
        val exactWords = try {
            // Read-only: this phrase is not authenticated yet, and the installed wallet's
            // mnemonic-based fallbacks validate against the app's current wordlist.
            MnemonicCodeExt.getInstance().checkExact(walletApplication, words, selectWordlist = false)
        } catch (x: MnemonicException) {
            null
        }
        return listOfNotNull(exactWords, words.map { it.lowercase(Locale.getDefault()) }).distinct()
    }
}
