/*
 * Copyright 2024 Dash Core Group.
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.dash.wallet.common.payments.parsers

import org.bitcoinj.core.Address
import org.bitcoinj.core.NetworkParameters
import org.checkerframework.checker.units.qual.Length

open class Bech32AddressParser(hrp: String, regex: String, params: NetworkParameters? = null) : AddressParser(
    "${hrp}$regex",
    params
) {
    constructor(hrp: String, length: Int, params: NetworkParameters?) : this(hrp, "1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]{$length}", params)
    constructor(length: Int, params: NetworkParameters) : this(params.segwitAddressHrp, "1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]{$length}", params)
    constructor(min: Int, max: Int, params: NetworkParameters) : this(params.segwitAddressHrp, "1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]{$min,$max}", params)

    override fun verifyAddress(addressCandidate: String) {
        params?.let { SegwitAddress.fromBech32(params, addressCandidate) }
    }
}
