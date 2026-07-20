/*
 * Copyright (c) 2024. Dash Core Group.
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

package org.dash.wallet.integrations.maya.payments

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Verifies that every currency registered in [MayaCurrencyList] declares an
 * [MayaCryptoCurrency.exampleAddress] that its own [MayaCryptoCurrency.addressParser]
 * recognises as an exact match. This guards against typos / format mismatches as new
 * coins and tokens are added to the list.
 */
class MayaCryptoCurrencyTest {

    @Test
    fun everyExampleAddressParsesWithItsOwnAddressParser() {
        val failures = MayaCurrencyList.all
            .filterNot { it.addressParser.exactMatch(it.exampleAddress) }
            .map { "${it.asset} (code=${it.code}): exampleAddress='${it.exampleAddress}'" }

        assertTrue(
            "The following currencies have an exampleAddress that does not match their " +
                "addressParser:\n" + failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    @Test
    fun everyExampleAddressIsFoundByItsOwnAddressParser() {
        // The address embedded in a larger string should be located by findAll().
        val failures = MayaCurrencyList.all
            .filterNot { currency ->
                currency.addressParser
                    .findAll("send to ${currency.exampleAddress} please")
                    .isNotEmpty()
            }
            .map { "${it.asset} (code=${it.code}): exampleAddress='${it.exampleAddress}'" }

        assertTrue(
            "The following currencies' exampleAddress was not found by findAll():\n" +
                failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    @Test
    fun currencyListIsNotEmpty() {
        assertFalse("MayaCurrencyList should not be empty", MayaCurrencyList.all.isEmpty())
    }

    @Test
    fun everyAssetResolvesViaLookup() {
        // associateBy({ it.asset }) silently drops duplicate assets; verify each
        // registered currency is reachable by its asset key.
        val unreachable = MayaCurrencyList.all
            .filter { MayaCurrencyList[it.asset] == null }
            .map { it.asset }

        assertTrue(
            "These assets are not reachable via MayaCurrencyList[asset]:\n" +
                unreachable.joinToString("\n"),
            unreachable.isEmpty()
        )
    }
}