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

package de.schildbach.wallet.service.platform.sdk

import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Context
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.evolution.AssetLockTransaction
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.ScriptBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The restore fallback of [AssetLockKindResolver]: an asset lock this install
 * did not author is classified by the account its credit output pays, the
 * evidence dashj's TxResourceMapper used.
 */
class AssetLockCreditAccountTest {

    private val params = TestNet3Params.get().also { Context.propagate(Context(it)) }

    @Test
    fun accountTypes_mapToTheDashjLabels() {
        assertEquals(AssetLockKind.UPGRADE, assetLockKindForCreditAccountTypes(setOf(2)))
        assertEquals(AssetLockKind.TOPUP, assetLockKindForCreditAccountTypes(setOf(3)))
        assertEquals(AssetLockKind.TOPUP, assetLockKindForCreditAccountTypes(setOf(4)))
        assertEquals(AssetLockKind.INVITE, assetLockKindForCreditAccountTypes(setOf(5)))
        // Not an identity account (BIP44, a Platform address top-up): no label.
        assertNull(assetLockKindForCreditAccountTypes(setOf(0, 6)))
        assertNull(assetLockKindForCreditAccountTypes(emptySet()))
        // A mix is not expected; registration wins, then invitation.
        assertEquals(AssetLockKind.UPGRADE, assetLockKindForCreditAccountTypes(setOf(3, 2)))
        assertEquals(AssetLockKind.INVITE, assetLockKindForCreditAccountTypes(setOf(3, 5)))
    }

    @Test
    fun creditAddresses_areParsedFromTheRawAssetLock() {
        val creditKey = ECKey()
        val lock = AssetLockTransaction(params, creditKey, Coin.COIN)
        lock.addInput(Sha256Hash.ZERO_HASH, 0, ScriptBuilder().build())

        val addresses = assetLockCreditAddresses(lock.bitcoinSerialize(), params)

        assertEquals(listOf(Address.fromKey(params, creditKey).toString()), addresses)
    }

    @Test
    fun creditAddresses_emptyForAnythingElse() {
        val plain = Transaction(params)
        plain.addInput(Sha256Hash.ZERO_HASH, 0, ScriptBuilder().build())
        plain.addOutput(Coin.COIN, Address.fromKey(params, ECKey()))
        assertTrue(assetLockCreditAddresses(plain.bitcoinSerialize(), params).isEmpty())
        assertTrue(assetLockCreditAddresses(byteArrayOf(1, 2, 3), params).isEmpty())
        assertTrue(assetLockCreditAddresses(ByteArray(0), params).isEmpty())
    }
}
