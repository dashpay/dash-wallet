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

package de.schildbach.wallet.ui.dashpay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Host-JVM tests for [ContactsIdentityGate], the one-shot-per-view identity
 * routing decision behind the contacts screen.
 *
 * Regression context: the resolved flag used to be a ContactsFragment field.
 * The fragment instance survives a back-stack pop while its view is
 * recreated, so onViewCreated hid the contacts container and the stale flag
 * made the observer ignore every subsequent identity emission — leaving a
 * blank white screen with a bare toolbar that needed a second BACK press to
 * escape. The gate is now created per view; these tests pin down both the
 * one-shot behavior within a view and the reset across views.
 */
class ContactsIdentityGateTest {

    @Test
    fun nullIdentity_doesNothing_untilFirstRealEmission() {
        val gate = ContactsIdentityGate()

        // DataStore hasn't emitted yet — must not route (the old synchronous
        // hasIdentity read misrouted username holders to EvoUpgrade here).
        assertEquals(ContactsIdentityRouting.NONE, gate.route(null))
        assertEquals(ContactsIdentityRouting.NONE, gate.route(null))

        // First real emission decides.
        assertEquals(ContactsIdentityRouting.SHOW_CONTACTS, gate.route(true))
    }

    @Test
    fun hasUsername_showsContacts_onceOnly() {
        val gate = ContactsIdentityGate()

        assertEquals(ContactsIdentityRouting.SHOW_CONTACTS, gate.route(true))

        // BlockchainIdentityData is not deduped and DataStore re-emits on any
        // preference change — repeats must not re-run routing or view setup.
        assertEquals(ContactsIdentityRouting.NONE, gate.route(true))
        assertEquals(ContactsIdentityRouting.NONE, gate.route(false))
        assertEquals(ContactsIdentityRouting.NONE, gate.route(null))
    }

    @Test
    fun noUsername_routesToEvoUpgrade_onceOnly() {
        val gate = ContactsIdentityGate()

        assertEquals(ContactsIdentityRouting.SHOW_EVO_UPGRADE, gate.route(false))

        // Repeated emissions must not navigate again.
        assertEquals(ContactsIdentityRouting.NONE, gate.route(false))
        assertEquals(ContactsIdentityRouting.NONE, gate.route(true))
    }

    @Test
    fun recreatedView_freshGate_resolvesAgain() {
        // View #1 of the fragment (e.g. before navigating forward).
        val firstViewGate = ContactsIdentityGate()
        assertEquals(ContactsIdentityRouting.SHOW_CONTACTS, firstViewGate.route(true))

        // BACK pops to the same fragment instance; its view is recreated with
        // the container hidden, and onViewCreated creates a fresh gate. The
        // first emission must show the contacts UI again — a shared/stale gate
        // is exactly the blank-screen bug.
        val recreatedViewGate = ContactsIdentityGate()
        assertEquals(ContactsIdentityRouting.SHOW_CONTACTS, recreatedViewGate.route(true))
    }
}
