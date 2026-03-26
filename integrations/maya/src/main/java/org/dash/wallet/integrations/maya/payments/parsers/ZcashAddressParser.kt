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

package org.dash.wallet.integrations.maya.payments.parsers

import org.dash.wallet.common.payments.parsers.AddressParser
import org.dash.wallet.common.payments.parsers.Bech32AddressParser

/**
 * Address parser for all Zcash address formats:
 * - Transparent: `t1...` (P2PKH) or `t3...` (P2SH) — Base58, 35 chars total
 * - Sapling shielded: `zs1...` — Bech32, 78 chars total
 * - Unified: `u1...` — Bech32m, variable length (91+ chars)
 */
class ZcashAddressParser : AddressParser("t[13][1-9A-HJ-NP-Za-km-z]{33}", null) {
    private val saplingParser = Bech32AddressParser("zs", 75, null) // zs1... Sapling shielded
    private val unifiedParser = Bech32AddressParser("u", 88, null) // u1... unified (min length)

    override fun exactMatch(inputText: String): Boolean {
        return super.exactMatch(inputText) ||
            saplingParser.exactMatch(inputText) ||
            unifiedParser.exactMatch(inputText)
    }

    override fun findAll(inputText: String): List<IntRange> {
        val result = arrayListOf<IntRange>()
        result.addAll(super.findAll(inputText))
        result.addAll(saplingParser.findAll(inputText))
        result.addAll(unifiedParser.findAll(inputText))
        return result
    }
}
