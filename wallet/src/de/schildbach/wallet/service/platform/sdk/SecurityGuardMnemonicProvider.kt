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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bitcoinj.crypto.KeyCrypterException
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.WalletDataProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production [PlatformMnemonicProvider]: extracts the BIP39 words from the
 * dashj wallet's key-chain seed, decrypting with the caller-supplied
 * PIN-derived key when the wallet is encrypted.
 *
 * ## Canonical decrypt path (kept in lock-step)
 *
 * This mirrors the app's one canonical seed-decrypt sequence — the same one
 * `SecurityFunctions.decryptSeed` / `DecryptSeedTask` (backup-seed and
 * verify-seed UI) run:
 *
 * ```
 * wallet.keyChainSeed.decrypt(wallet.keyCrypter, null, pinDerivedKey)
 * ```
 *
 * where `pinDerivedKey` comes from `SecurityFunctions.deriveKey(wallet,
 * securityGuard.retrievePassword())` AFTER the caller has authenticated the
 * user. That derivation (and any scrypt-parameter upgrade it performs) is
 * deliberately left to the caller so this provider stays free of prompting
 * and of wallet-mutating side effects — see [WalletUnlock] for the trust
 * model.
 *
 * ## Seed-material hygiene
 *
 * The words are returned as [String]s (immutable, not scrubbable) because
 * both the existing app surface (`DecryptSeedViewModel`, dashj's
 * `DeterministicSeed.mnemonicCode`) and the SDK sink
 * (`PlatformWalletManager.createWallet(mnemonic: String)`) already traffic
 * in strings — there is no byte-array path to preserve. Callers must never
 * log or persist them; hand them straight to
 * [DashSdkService.bindAppWallet] and drop the reference.
 */
@Singleton
class SecurityGuardMnemonicProvider @Inject constructor(
    private val walletData: WalletDataProvider
) : PlatformMnemonicProvider {

    override suspend fun getMnemonicWords(
        unlock: WalletUnlock
    ): List<String> = withContext(Dispatchers.Default) {
        when (unlock) {
            is WalletUnlock.DecryptedSeed -> wordsOf(unlock.seed)

            is WalletUnlock.EncryptionKey -> {
                val wallet = walletOrThrow()
                val seed = seedOrThrow(wallet)
                if (!seed.isEncrypted) {
                    // Lenient: a key against an unencrypted (dev) wallet is
                    // merely unnecessary, not an error.
                    wordsOf(seed)
                } else {
                    val keyCrypter = wallet.keyCrypter
                        // Encrypted seed but no crypter on the wallet:
                        // corrupt state, not a caller mistake.
                        ?: throw MnemonicBridgeException(
                            MnemonicBridgeException.Reason.SEED_MISSING
                        )
                    val decrypted = try {
                        // Takes time (AES over the scrypt-derived key) —
                        // hence Dispatchers.Default, matching
                        // SecurityFunctions.decryptSeed.
                        seed.decrypt(keyCrypter, null, unlock.key)
                    } catch (e: KeyCrypterException) {
                        throw MnemonicBridgeException(
                            MnemonicBridgeException.Reason.BAD_ENCRYPTION_KEY, e
                        )
                    }
                    wordsOf(decrypted)
                }
            }

            WalletUnlock.Unencrypted -> {
                val seed = seedOrThrow(walletOrThrow())
                if (seed.isEncrypted) {
                    throw MnemonicBridgeException(
                        MnemonicBridgeException.Reason.ENCRYPTION_KEY_REQUIRED
                    )
                }
                wordsOf(seed)
            }
        }
    }

    private fun walletOrThrow(): Wallet = walletData.wallet
        ?: throw MnemonicBridgeException(MnemonicBridgeException.Reason.WALLET_UNAVAILABLE)

    /**
     * The active key chain's seed. dashj throws
     * `IKey.MissingPrivateKeyException` (watch-only chain) or
     * `DeterministicUpgradeRequiredException` (pre-HD wallet) instead of
     * returning null — both mean "no seed to bridge".
     */
    private fun seedOrThrow(wallet: Wallet): DeterministicSeed = try {
        wallet.keyChainSeed
            ?: throw MnemonicBridgeException(MnemonicBridgeException.Reason.SEED_MISSING)
    } catch (e: MnemonicBridgeException) {
        throw e
    } catch (e: Exception) {
        throw MnemonicBridgeException(MnemonicBridgeException.Reason.SEED_MISSING, e)
    }

    private fun wordsOf(seed: DeterministicSeed): List<String> {
        if (seed.isEncrypted) {
            // A DecryptedSeed unlock carrying a still-encrypted seed.
            throw MnemonicBridgeException(
                MnemonicBridgeException.Reason.ENCRYPTION_KEY_REQUIRED
            )
        }
        val words = seed.mnemonicCode
        if (words.isNullOrEmpty()) {
            // Raw-entropy seed (no BIP39 phrase) — nothing to bridge.
            throw MnemonicBridgeException(MnemonicBridgeException.Reason.MNEMONIC_MISSING)
        }
        return words.toList()
    }
}
