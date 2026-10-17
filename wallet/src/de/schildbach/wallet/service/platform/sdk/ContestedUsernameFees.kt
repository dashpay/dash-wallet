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

import de.schildbach.wallet.Constants
import org.bitcoinj.core.Coin

/**
 * Platform protocol version at/after which the v4.2 contested-username fee
 * cut (MO-1069) is active on the network.
 */
private const val CONTESTED_FEE_PROTOCOL_VERSION = 14

/**
 * Thrown by [ContestedUsernameFees.resolved] when the live protocol-version
 * read returns null: at funding time an unknown version must not silently
 * select a fee, because the older fee is the HIGHER one.
 */
class ProtocolVersionUnavailableException :
    IllegalStateException("Platform protocol version unavailable; contested fee cannot be resolved")

/**
 * The contested-username fee pair ACTUALLY in effect on the network right
 * now, gated on [DashSdkService.currentProtocolVersion] rather than assumed
 * from the release train: a build can ship before or after the network
 * itself activates v4.2, and the two must never drift — a stale 0.15/0.10
 * assumed against a still-pre-v4.2 network causes a real DPNS registration
 * rejection (insufficient identity balance).
 *
 * [CURRENT] (0.15 / 0.10) applies once the network reports protocol >= 14;
 * [LEGACY] (0.25 / 0.20) applies to an older protocol version.
 *
 * A null (unknown/unreachable) read is handled by WHO is asking:
 *  - [current] — quotes and display — falls back to [LEGACY], the
 *    conservative figure to show;
 *  - [resolved] — funding, where money leaves the wallet — throws
 *    [ProtocolVersionUnavailableException] instead. Falling back there would
 *    let a lower successful quote be followed by a higher actual funding
 *    whenever the second lookup fails; every funding service already turns a
 *    throwing fee resolver into a refusal the user can retry.
 */
data class ContestedUsernameFees(val contested: Coin, val contestedName: Coin) {
    companion object {
        val CURRENT = ContestedUsernameFees(
            Constants.DASH_PAY_FEE_CONTESTED,
            Constants.DASH_PAY_FEE_CONTESTED_NAME
        )
        val LEGACY = ContestedUsernameFees(
            Constants.DASH_PAY_FEE_CONTESTED_LEGACY,
            Constants.DASH_PAY_FEE_CONTESTED_NAME_LEGACY
        )

        fun forProtocolVersion(protocolVersion: Int?): ContestedUsernameFees =
            if (protocolVersion != null && protocolVersion >= CONTESTED_FEE_PROTOCOL_VERSION) {
                CURRENT
            } else {
                LEGACY
            }

        /**
         * Resolve the fee pair from a live [dashSdkService] protocol-version read,
         * for quotes and display: an unknown version shows the [LEGACY] figure.
         */
        suspend fun current(dashSdkService: DashSdkService): ContestedUsernameFees =
            forProtocolVersion(dashSdkService.currentProtocolVersion())

        /**
         * Resolve the fee pair for FUNDING: the amount about to leave the wallet
         * must come from a known protocol version, so a null read throws
         * [ProtocolVersionUnavailableException] rather than defaulting to the
         * higher [LEGACY] fee.
         */
        suspend fun resolved(dashSdkService: DashSdkService): ContestedUsernameFees =
            forProtocolVersion(
                dashSdkService.currentProtocolVersion() ?: throw ProtocolVersionUnavailableException()
            )
    }
}

/**
 * Top-up an existing identity needs for a contested name under [fees], given its
 * balance in Platform credits (duffs × 1000) — or null when the credits already
 * cover [ContestedUsernameFees.contested] and no transaction must be built. Pure:
 * the resume path in CreateIdentityService decides AND sizes the top-up from the
 * same strictly resolved pair through this, so a conservative pre-check can never
 * turn into an unnecessary funding.
 */
fun contestedTopUpFor(identityBalanceCredits: Long, fees: ContestedUsernameFees): Coin? =
    if (identityBalanceCredits < fees.contested.value * 1000) fees.contestedName else null
