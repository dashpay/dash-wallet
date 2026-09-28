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

import androidx.annotation.VisibleForTesting
import org.bitcoinj.core.Coin
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * What a contested (DPNS) username costs, which depends on the Platform protocol
 * version the network runs.
 *
 * A contested name prefunds its vote resolution with 0.2 DASH through protocol
 * version 13; protocol version 14 (Platform v4.2) lowers that to 0.1 DASH. The
 * protocol version is learned from the network by [PlatformRepo.refreshProtocolVersion].
 * Until it is known the older, higher fees apply: funding an identity above the fee
 * only leaves the surplus as identity credits, while funding it below makes the
 * registration fail.
 */
object ContestedUsernameFees {
    const val REDUCED_FEE_PROTOCOL_VERSION = 14

    // 2,500,000,000 credits: 0.2 DASH prefunded voting balance + identity/document fees
    private val FEE_BEFORE_V14: Coin = Coin.parseCoin("0.25")
    private val NAME_FEE_BEFORE_V14: Coin = Coin.parseCoin("0.20")

    // 1,500,000,000 credits: 0.1 DASH prefunded voting balance + identity/document fees
    private val FEE: Coin = Coin.parseCoin("0.15")
    private val NAME_FEE: Coin = Coin.parseCoin("0.10")

    private val protocolVersion = AtomicInteger(0)

    /** The highest Platform protocol version seen so far, 0 if none. */
    val currentProtocolVersion: Int
        get() = protocolVersion.get()

    /** Records a protocol version from the network. The stored version only goes up. */
    fun updateProtocolVersion(version: Int) {
        protocolVersion.accumulateAndGet(version, ::max)
    }

    /** Creating an identity together with a contested username: the asset lock amount. */
    val fee: Coin
        get() = feeFor(currentProtocolVersion)

    /** Registering a contested username for an identity that already exists. */
    val nameFee: Coin
        get() = nameFeeFor(currentProtocolVersion)

    fun feeFor(protocolVersion: Int): Coin =
        if (protocolVersion >= REDUCED_FEE_PROTOCOL_VERSION) FEE else FEE_BEFORE_V14

    fun nameFeeFor(protocolVersion: Int): Coin =
        if (protocolVersion >= REDUCED_FEE_PROTOCOL_VERSION) NAME_FEE else NAME_FEE_BEFORE_V14

    @VisibleForTesting
    fun reset() {
        protocolVersion.set(0)
    }
}
