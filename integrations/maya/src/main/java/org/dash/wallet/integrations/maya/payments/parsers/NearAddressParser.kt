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
 *  - named accounts: 2-64 chars of `[a-z0-9_-]` separated by `.`,
 *    typically ending in `.near`
 */
class NearAddressParser : AddressParser(
    "([a-f0-9]{64})|([a-z0-9_-]{2,64}(\\.[a-z0-9_-]{1,64})+)",
    null
)
