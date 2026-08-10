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

/**
 * Seam over the Kotlin SDK's message-signing surface
 * (`ManagedPlatformWallet.signMessage`), so
 * [de.schildbach.wallet.security.SecurityFunctions.signMessage] and its
 * error mapping stay host-JVM unit-testable — the real call needs
 * `libdash_sdk`. Same shape as [SdkL1SendSource].
 */
interface SdkMessageSigner {
    /**
     * Sign [message] with the private key of [address] (Dash "magic
     * message" signing — the same scheme CrowdNode verifies), returning the
     * base64 signature.
     *
     * Throws on every failure; notably
     * `DashSdkError.PlatformWallet.SigningKeyUnavailable` when the bound
     * wallet does not own [address], and
     * `DashSdkError.PlatformWallet.Generic` with `nativeCode == 2`
     * (`ErrorInvalidParameter`) when [address] is malformed. Callers map
     * these onto
     * [org.dash.wallet.common.services.MessageSigningException].
     */
    suspend fun signMessage(address: String, message: String): String
}

/**
 * Production [SdkMessageSigner]: boots the SDK on demand and signs with the
 * single bound wallet.
 *
 * The signer handle is the manager's `mnemonicResolverHandle` — the same
 * handle every SDK write path uses ([DashSdkL1SendSource.sendToAddress],
 * [SdkL1InviteCreation], …). The seed never crosses the FFI boundary: the
 * resolver reads it Rust-side from the SDK's Keystore-backed
 * `WalletStorage`, which [DashSdkService.bindAppWallet] populated at bind
 * time. That is why signing needs NO PIN prompt here — unlike the dashj
 * implementation this replaces, which had to retrieve the password and
 * derive the wallet's encryption key on every call.
 */
class DashSdkMessageSigner @Inject constructor(
    private val service: DashSdkService
) : SdkMessageSigner {

    private suspend fun manager(): org.dashfoundation.dashsdk.wallet.PlatformWalletManager {
        service.ensureStarted()
        return checkNotNull(service.walletManagerOrNull()) {
            "SDK wallet manager missing after ensureStarted()"
        }
    }

    override suspend fun signMessage(address: String, message: String): String {
        val manager = manager()
        // ONE app wallet by construction; more than one entry means stale
        // leftovers of a reset/wiped wallet, which `singleOrNull()` refuses
        // rather than signing with an arbitrary one (the established
        // bound-wallet lookup — see [DashSdkService.loadedWalletIds]).
        val walletIdHex = checkNotNull(manager.wallets.value.keys.singleOrNull()) {
            "no single bound SDK wallet to sign with"
        }
        val wallet = checkNotNull(manager.wallets.value[walletIdHex]) { "SDK wallet not loaded" }

        return wallet.signMessage(
            address = address,
            message = message,
            coreSignerHandle = manager.mnemonicResolverHandle
        )
    }
}
