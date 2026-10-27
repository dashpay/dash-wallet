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

package org.dash.wallet.features.exploredash

import org.dash.wallet.features.exploredash.data.explore.model.Merchant
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchResultAddressTest {
    private fun merchant(
        address1: String? = "",
        city: String? = "",
        territory: String? = "",
        merchantSource: String = "CTX"
    ) = Merchant().apply {
        this.address1 = address1
        this.city = city
        this.territory = territory
        this.source = merchantSource
    }

    @Test
    fun fullAddress_rendersStreetCityTerritory() {
        val address = merchant(address1 = "2171 Kent Dairy Rd", city = "Alabaster", territory = "Alabama")
            .getDisplayAddress(", ")

        assertEquals("2171 Kent Dairy Rd, Alabaster, Alabama", address)
    }

    @Test
    fun emptyRecord_rendersNothingInsteadOfStraySeparators() {
        // Regression test: explore records can arrive with no street, city or territory.
        // Those rows used to render as ", , " in the merchant locations list.
        assertEquals("", merchant().getDisplayAddress(", "))
    }

    @Test
    fun nullRecord_rendersNothingInsteadOfTheWordNull() {
        assertEquals("", merchant(address1 = null, city = null, territory = null).getDisplayAddress(", "))
    }

    @Test
    fun missingStreet_doesNotLeaveALeadingSeparator() {
        val address = merchant(address1 = "", city = "Alabaster", territory = "Alabama").getDisplayAddress(", ")

        assertEquals("Alabaster, Alabama", address)
    }

    @Test
    fun missingCity_doesNotLeaveASeparatorBeforeTheTerritory() {
        val address = merchant(address1 = "2171 Kent Dairy Rd", city = "", territory = "Alabama")
            .getDisplayAddress(", ")

        assertEquals("2171 Kent Dairy Rd, Alabama", address)
    }

    @Test
    fun nonCtxSource_ignoresCityAndTerritory() {
        val address = merchant(
            address1 = "1 Main St",
            city = "Alabaster",
            territory = "Alabama",
            merchantSource = "DCG"
        )
            .getDisplayAddress(", ")

        assertEquals("1 Main St", address)
    }
}
