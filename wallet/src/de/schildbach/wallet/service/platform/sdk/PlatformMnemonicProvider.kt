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

import org.bitcoinj.wallet.DeterministicSeed
import org.bouncycastle.crypto.params.KeyParameter

/**
 * Proof of an already-completed wallet unlock, passed INTO
 * [PlatformMnemonicProvider.getMnemonicWords] — Phase 3b of the
 * dashj → Kotlin SDK migration (`docs/kotlin-sdk-migration-plan.md`).
 *
 * ## Trust model (load-bearing)
 *
 * The provider NEVER prompts and NEVER touches `SecurityGuard` itself:
 * authenticating the user (PIN/biometric via
 * [org.dash.wallet.common.services.AuthenticationManager.authenticate]),
 * recovering the wallet password
 * ([de.schildbach.wallet.security.SecurityGuard.retrievePassword]) and
 * deriving the AES key
 * ([de.schildbach.wallet.security.SecurityFunctions.deriveKey]) are all the
 * CALLER's job. Whoever constructs a [WalletUnlock] asserts that the user
 * has already authorized this seed access.
 */
sealed class WalletUnlock {

    /**
     * The wallet's key-crypter AES key, derived from the PIN-protected
     * wallet password via
     * [de.schildbach.wallet.security.SecurityFunctions.deriveKey] — the
     * canonical unlock for the app's encrypted production wallets.
     */
    class EncryptionKey(val key: KeyParameter) : WalletUnlock()

    /**
     * An already-decrypted seed (e.g. from an in-flight
     * [de.schildbach.wallet.payments.DecryptSeedTask] result); the provider
     * only extracts the words. Must not be encrypted.
     */
    class DecryptedSeed(val seed: DeterministicSeed) : WalletUnlock()

    /**
     * No credentials: only valid for wallets that were never PIN-encrypted
     * (development/test wallets). Fails with
     * [MnemonicBridgeException.Reason.ENCRYPTION_KEY_REQUIRED] against an
     * encrypted wallet.
     */
    object Unencrypted : WalletUnlock()
}

/**
 * Typed failure of the mnemonic bridge, so Phase 3c flows can branch on
 * [reason] instead of string-matching dashj exceptions.
 */
class MnemonicBridgeException(
    val reason: Reason,
    cause: Throwable? = null
) : Exception("BIP39 mnemonic unavailable: $reason", cause) {

    enum class Reason {
        /** No dashj wallet is loaded (`WalletDataProvider.wallet == null`). */
        WALLET_UNAVAILABLE,

        /** The active key chain has no seed — watch-only or corrupt wallet. */
        SEED_MISSING,

        /** A seed exists but carries no BIP39 words (raw-entropy seed). */
        MNEMONIC_MISSING,

        /** The seed is encrypted and no [WalletUnlock.EncryptionKey] was given. */
        ENCRYPTION_KEY_REQUIRED,

        /** Decryption failed — wrong PIN-derived key (or corrupt ciphertext). */
        BAD_ENCRYPTION_KEY,
    }
}

/**
 * Seam through which the app's BIP39 seed reaches the Kotlin SDK — Phase 3b
 * of the dashj → Kotlin SDK migration.
 *
 * The SDK resolves mnemonics through its own Keystore-backed
 * `WalletStorage` / `MnemonicResolverAndPersister` pair; the app's seed
 * lives in the dashj wallet file, PIN-encrypted under `SecurityGuard`. This
 * interface hands the decrypted words to the ONE call that moves them
 * across ([DashSdkService.bindAppWallet], whose `createWallet` persists
 * them into the SDK's `WalletStorage`); after that the SDK's resolver
 * serves all derivations on its own.
 *
 * The production binding is [SecurityGuardMnemonicProvider].
 */
interface PlatformMnemonicProvider {

    /**
     * The wallet's BIP39 mnemonic words, for handing to
     * [DashSdkService.bindAppWallet] exactly once at wallet-binding time.
     *
     * [unlock] carries the caller's proof of user authorization (see
     * [WalletUnlock] for the trust model) — implementations never prompt.
     * Never call this on a background sync path, and never log the result.
     *
     * @throws MnemonicBridgeException with a typed [MnemonicBridgeException.Reason]
     *   for every failure mode (missing wallet/seed/words, missing or wrong key).
     */
    suspend fun getMnemonicWords(unlock: WalletUnlock): List<String>
}
