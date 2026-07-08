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

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seam through which the app's BIP39 seed will reach the Kotlin SDK —
 * Phase 3b of the dashj → Kotlin SDK migration
 * (`docs/kotlin-sdk-migration-plan.md`).
 *
 * The SDK resolves mnemonics through its own Keystore-backed
 * `WalletStorage` / `MnemonicResolverAndPersister` pair. The app's seed,
 * however, lives in the dashj wallet file, PIN-encrypted under
 * `SecurityGuard`. Phase 3b will implement this interface to decrypt the
 * dashj seed (with the user's PIN, at an explicit user action) and feed it
 * to the SDK — e.g. via `WalletStorage.storeMnemonic` or a wallet-creation
 * call — after which the SDK's resolver serves derivations on its own.
 *
 * Until then the only binding is [Phase3bPlaceholderMnemonicProvider],
 * which fails loudly so no code path can silently run against a missing
 * seed.
 */
interface PlatformMnemonicProvider {

    /**
     * The wallet's BIP39 mnemonic phrase, for handing to the SDK exactly
     * once at wallet-binding time. Implementations must require explicit
     * user authorization (PIN) — never call this on a background sync path.
     */
    suspend fun getMnemonic(): String
}

/**
 * Phase 3 placeholder: the SDK bootstrap scaffold never needs a mnemonic
 * (no SDK wallets exist yet, so `loadPersistedWallets()` restores nothing
 * and the SDK's resolver is never consulted). Any premature attempt to
 * bind the app's seed fails with a clear signal instead of undefined
 * behavior.
 */
@Singleton
class Phase3bPlaceholderMnemonicProvider @Inject constructor() : PlatformMnemonicProvider {

    override suspend fun getMnemonic(): String =
        throw UnsupportedOperationException("wallet binding lands in Phase 3b")
}
