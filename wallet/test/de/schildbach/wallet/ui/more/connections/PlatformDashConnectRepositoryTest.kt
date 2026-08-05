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

package de.schildbach.wallet.ui.more.connections

import de.schildbach.wallet.Constants
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.service.platform.PlatformService
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.more.connections.protocol.DashConnectUriException
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Base58
import org.bitcoinj.core.ECKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.math.BigInteger

/**
 * Repository-level tests for the parts of [PlatformDashConnectRepository] that do not touch the
 * native Platform SDK: QR classification, network gating and testnet gating. The full
 * approve/complete paths require a live rust SDK and are exercised on-device.
 *
 * These run only under the testnet flavor (where the feature is enabled).
 */
class PlatformDashConnectRepositoryTest {

    private val platform = mockk<PlatformService>(relaxed = true)
    private val platformRepo = mockk<PlatformRepo>(relaxed = true)
    private val identityRepository = mockk<IdentityRepository>(relaxed = true)
    private val config = mockk<DashConnectConfig>(relaxed = true)

    private val repository = PlatformDashConnectRepository(platform, platformRepo, identityRepository, config)

    private fun appPublicKey(): ByteArray =
        ECKey.fromPrivate(BigInteger(1, ByteArray(32) { 0x01 }), true).pubKey

    private fun testnetKeyUri(): String {
        val payload = ByteArray(67)
        payload[0] = 0x01
        System.arraycopy(appPublicKey(), 0, payload, 1, 33)
        System.arraycopy(ByteArray(32) { 0xcd.toByte() }, 0, payload, 34, 32)
        payload[66] = 0 // empty label
        return "dash-key:${Base58.encode(payload)}?n=t&v=1"
    }

    @Test
    fun parseQr_classifiesKeyUriAsLogin_onTestnet() = runBlocking {
        assumeTrue("testnet-only feature", Constants.IS_TESTNET_BUILD)
        val result = repository.parseQr(testnetKeyUri())
        assertTrue(result is DashConnectQr.Login)
        assertEquals("cd".repeat(32), org.bitcoinj.core.Utils.HEX.encode((result as DashConnectQr.Login).request.contractId))
    }

    @Test
    fun parseQr_classifiesStUriAsKeyRegistration_onTestnet() = runBlocking {
        assumeTrue("testnet-only feature", Constants.IS_TESTNET_BUILD)
        val uri = "dash-st:${Base58.encode(ByteArray(40) { it.toByte() })}?n=t&v=1"
        val result = repository.parseQr(uri)
        assertTrue(result is DashConnectQr.KeyRegistration)
    }

    @Test
    fun parseQr_rejectsMainnetQrOnTestnet() = runBlocking {
        assumeTrue("testnet-only feature", Constants.IS_TESTNET_BUILD)
        val payload = ByteArray(67).also {
            it[0] = 0x01
            System.arraycopy(appPublicKey(), 0, it, 1, 33)
        }
        val mainnetUri = "dash-key:${Base58.encode(payload)}?n=m&v=1"
        assertThrows(DashConnectUriException::class.java) {
            runBlocking { repository.parseQr(mainnetUri) }
        }
        Unit
    }

    @Test
    fun parseQr_rejectsNonDashConnectContent() = runBlocking {
        assumeTrue("testnet-only feature", Constants.IS_TESTNET_BUILD)
        assertThrows(DashConnectUriException::class.java) {
            runBlocking { repository.parseQr("https://example.com") }
        }
        Unit
    }
}
