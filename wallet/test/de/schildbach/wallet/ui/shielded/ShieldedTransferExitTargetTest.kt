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

package de.schildbach.wallet.ui.shielded

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Host-JVM tests for [shieldedTransferExitTarget] — where dismissing a
 * terminal, non-success shielded-transfer outcome leaves the flow (Bug 1).
 * A shield-first entry sits on top of the create-username/invite stack, so
 * it must clear straight home instead of unwinding that stack one screen at
 * a time; the More/payments-card entry just finishes back to the card.
 */
class ShieldedTransferExitTargetTest {

    @Test
    fun `shield-first entry leaves straight to home`() {
        assertEquals(
            ShieldedExitTarget.HOME,
            shieldedTransferExitTarget(shieldFirst = true)
        )
    }

    @Test
    fun `More or payments card entry just finishes`() {
        assertEquals(
            ShieldedExitTarget.FINISH,
            shieldedTransferExitTarget(shieldFirst = false)
        )
    }
}
