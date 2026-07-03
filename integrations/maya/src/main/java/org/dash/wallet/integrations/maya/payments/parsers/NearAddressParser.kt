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
 * NEAR account address parser:
 *  - implicit accounts: 64 lowercase hex characters
 *  - named accounts: dot-separated labels (typically ending in `.near`); each label is
 *    lowercase alphanumeric with single `-`/`_` separators that can't lead or trail a
 *    label (NEAR account-id grammar), max 64 characters overall
 *
 * Named accounts carry no checksum, so this grammar is the only structural gate before
 * the address is used as a refund/source address.
 */
class NearAddressParser : AddressParser(
    "([a-f0-9]{64})|((([a-z0-9]+[-_])*[a-z0-9]+\\.)+([a-z0-9]+[-_])*[a-z0-9]+)",
    null
) {
    override fun verifyAddress(addressCandidate: String) {
        // The grammar bounds each label but not the account id itself — NEAR caps it at 64.
        require(addressCandidate.length in 2..64) {
            "NEAR account ids are 2-64 characters"
        }
    }
}
