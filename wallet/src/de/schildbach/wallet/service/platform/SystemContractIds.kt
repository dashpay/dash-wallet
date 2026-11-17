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

package de.schildbach.wallet.service.platform

import de.schildbach.wallet.Constants
import org.bitcoinj.core.NetworkParameters
import org.dashj.platform.dpp.identifier.Identifier
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wallet-owned registry of the Platform data-contract IDs the wallet uses, keyed by the same app
 * names the legacy `org.dashj.platform.sdk.platform.Platform` client registers in its `apps` map.
 *
 * Pure: it never constructs `Platform`, touches the network or loads a native library, so the SDK
 * adapters and `hasApp` guards can read a contract ID without creating the legacy client.
 *
 * The mapping reproduces the `Platform` constructor of the pinned dash-sdk-kotlin 5.0.1 exactly:
 * - `dpns`, `dashpay` and `wallet-utils` are system contracts with the same ID on every network
 *   (the legacy `SystemIds` values);
 * - `identity-verify` depends on [NetworkParameters.getId], matched by case-sensitive substring
 *   in the legacy order (`"test"`, then `"bintang"`, then `"production"`). Any other network,
 *   including the Sakura devnet (`org.dash.dev.devnet-sakura`), has no identity-verify contract:
 *   the name is known but its ID is null.
 */
@Singleton
class SystemContractIds internal constructor(params: NetworkParameters) {

    @Inject
    constructor() : this(Constants.NETWORK_PARAMETERS)

    private val ids: Map<String, Identifier?> = mapOf(
        DPNS to DPNS_CONTRACT_ID,
        DASHPAY to DASHPAY_CONTRACT_ID,
        WALLET_UTILS to WALLET_UTILS_CONTRACT_ID,
        IDENTITY_VERIFY to identityVerifyContractId(params.id)
    )

    /** The contract ID registered under [appName] on this network, or null when there is none. */
    fun contractId(appName: String): Identifier? = ids[appName]

    /**
     * Whether [appName] has a contract on this network, answering like legacy `Platform.hasApp`
     * for the names this registry owns; null when the registry does not know [appName] (for
     * example a custom app a legacy caller registered at runtime), so the caller must ask the
     * legacy client.
     */
    fun isRegistered(appName: String): Boolean? =
        if (ids.containsKey(appName)) ids[appName] != null else null

    companion object {
        const val DPNS = "dpns"
        const val DASHPAY = "dashpay"
        const val WALLET_UTILS = "wallet-utils"
        const val IDENTITY_VERIFY = "identity-verify"

        // Canonical system IDs (dashpay/platform packages/*-contract/lib/systemIds.js).
        private val DPNS_CONTRACT_ID = Identifier.from("GWRSAVFMjXx8HpQFaNJMqBV7MBgMK4br5UESsB4S31Ec")
        private val DASHPAY_CONTRACT_ID = Identifier.from("Bwr4WHCPz5rFVAD87RqTs3izo4zpzwsEdKPWUT1NS1C7")
        private val WALLET_UTILS_CONTRACT_ID = Identifier.from("7CSFGeF4WNzgDmx94zwvHkYaG3Dx4XEe5LFsFgJswLbm")

        private val IDENTITY_VERIFY_TESTNET = Identifier.from("Bhptm3yBDhLkRNt7ofjpwaBHhMUKjDrQoPufKzQaxmpK")
        private val IDENTITY_VERIFY_BINTANG = Identifier.from("Fds5DDfXoLwpUZ71AAVYZP1uod8S7Ze2bR28JExBvZKR")
        private val IDENTITY_VERIFY_MAINNET = Identifier.from("EVKMFboB3QBUa9Jo7PP5bsLyohzUz8zvw5c2gJs1SfcX")

        /** The legacy `Platform` constructor's `when` over `params.id`, in the same order. */
        private fun identityVerifyContractId(networkId: String): Identifier? = when {
            networkId.contains("test") -> IDENTITY_VERIFY_TESTNET
            networkId.contains("bintang") -> IDENTITY_VERIFY_BINTANG
            networkId.contains("production") -> IDENTITY_VERIFY_MAINNET
            else -> null
        }
    }
}
