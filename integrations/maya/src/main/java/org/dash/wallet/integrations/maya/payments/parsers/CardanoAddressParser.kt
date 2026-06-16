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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.dash.wallet.integrations.maya.payments.parsers

import org.dash.wallet.common.payments.parsers.AddressParser

/**
 * Cardano address parser — Shelley Bech32 addresses (HRP `addr`, length ~98)
 * or legacy Byron Base58 (`Ae2tdPwUPEZ...` / `DdzFFzCqr...`).
 */
class CardanoAddressParser : AddressParser(
    "(addr1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]{53,98})|((Ae2|DdzFF)[1-9A-HJ-NP-Za-km-z]{50,110})",
    null
)
