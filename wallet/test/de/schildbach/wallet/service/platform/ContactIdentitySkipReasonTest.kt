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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Optional

/**
 * The contact-keychain steps skip a contact whose identity lookup came back
 * empty instead of force-unwrapping it (field log, 10-04 04:26–04:36: seven
 * "check and add received/sent requests: error: Unknown error -
 * NullPointerException", each right after "legacy platform query timed out").
 */
class ContactIdentitySkipReasonTest {

    @Test
    fun timedOutLookup_isSkipped() {
        // boundedLegacyPlatformQuery returns null when its budget expires.
        assertEquals("the contact identity lookup timed out", contactIdentitySkipReason(null))
    }

    @Test
    fun missingIdentity_isSkipped() {
        assertEquals(
            "the contact identity was not found on Platform",
            contactIdentitySkipReason(Optional.empty<Any>())
        )
    }

    @Test
    fun foundIdentity_isNotSkipped() {
        assertNull(contactIdentitySkipReason(Optional.of("identity")))
    }
}
