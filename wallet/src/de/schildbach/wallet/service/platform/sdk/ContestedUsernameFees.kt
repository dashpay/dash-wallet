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
 * The contested-username fee pair ACTUALLY in effect on the network right
 * now, gated on [DashSdkService.currentProtocolVersion] rather than assumed
 * from the release train: a build can ship before or after the network
 * itself activates v4.2, and the two must never drift — a stale 0.15/0.10
 * assumed against a still-pre-v4.2 network causes a real DPNS registration
 * rejection (insufficient identity balance).
 *
 * [current] (0.15 / 0.10) applies once the network reports protocol >= 14;
 * [legacy] (0.25 / 0.20) is the fallback for every OTHER case — an older
 * protocol version, or a null (unknown/unreachable) read, per
 * [DashSdkService.currentProtocolVersion]'s contract that null must fall
 * back to the OLDER behavior.
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

        /** Resolve the fee pair from a live [dashSdkService] protocol-version read. */
        suspend fun current(dashSdkService: DashSdkService): ContestedUsernameFees =
            forProtocolVersion(dashSdkService.currentProtocolVersion())
    }
}
