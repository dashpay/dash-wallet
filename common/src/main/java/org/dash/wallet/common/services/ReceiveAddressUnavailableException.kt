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

package org.dash.wallet.common.services

/**
 * No receive address can be served right now.
 *
 * Raised only POST-CUTOVER, when the SDK engine cannot answer and the wallet
 * therefore has no address it is safe to advertise. It exists because the
 * obvious fallback is actively wrong: the dashj key chain is HELD after the
 * cutover, so its "current" pointer is frozen wherever the restore left it —
 * index 0 on a fresh restore, an address the chain has ALREADY been paid on.
 * Serving that is the SR-03 defect.
 *
 * Callers must surface a retry rather than substitute an address. The condition
 * is transient by nature: it covers the window before the engine binds, a failed
 * engine read, and the instant after a wallet wipe revokes the binding.
 */
class ReceiveAddressUnavailableException(
    message: String = "no receive address is available yet"
) : IllegalStateException(message)
