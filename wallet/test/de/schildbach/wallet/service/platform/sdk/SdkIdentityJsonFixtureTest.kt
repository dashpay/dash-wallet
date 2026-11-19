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

import android.app.Application
import de.schildbach.wallet.ui.more.connections.AppConnectKeyStateTest
import org.bitcoinj.core.Base58
import org.bitcoinj.core.Utils
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.dpp.identity.ContractBounds
import org.dashj.platform.dpp.identity.ContractGroupBounds
import org.dashj.platform.dpp.identity.SingleContractBounds
import org.dashj.platform.dpp.identity.SingleContractDocumentType
import org.dashj.platform.sdk.KeyType
import org.dashj.platform.sdk.Purpose
import org.dashj.platform.sdk.SecurityLevel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigInteger

/**
 * [SdkIdentityJson] against a real SDK response rather than hand-written JSON.
 *
 * The fixture `test/sdk-identity-by-public-key-hash-testnet.json` is, byte for byte, the string the
 * Kotlin SDK `5.1.0-int36-20261106.165805-1` returned from `identities.fetchByPublicKeyHash` for a
 * testnet identity on 2026-11-18. It has V0 and V1 keys, all three contract-bound types,
 * `ECDSA_HASH160` keys and disabled keys carrying `disabledAt`, `totalBudget` and `expiresAt`.
 *
 * The expected values below are not derived from the fixture: they are the legacy client's own
 * model of the same identity, fetched in the same run (`IdentityPublicKey` fields per key, plus
 * the bounds' identifier and document type, balance and revision from the legacy `toJSON`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    application = Application::class,
    sdk = [29],
    manifest = Config.NONE,
    shadows = [AppConnectKeyStateTest.ShadowSdkEnums::class]
)
class SdkIdentityJsonFixtureTest {

    private data class Bounds(val type: Class<out ContractBounds>, val identifier: String, val documentType: String? = null)

    private data class ExpectedKey(
        val id: Int,
        val type: KeyType,
        val purpose: Purpose,
        val securityLevel: SecurityLevel,
        val readOnly: Boolean,
        val disabledAt: Long?,
        val bounds: Bounds?,
        val version: Int,
        val totalBudget: BigInteger?,
        val expiresAt: Long?,
        val dataHex: String
    )

    private companion object {
        const val FIXTURE = "/sdk-identity-by-public-key-hash-testnet.json"
        const val IDENTITY_ID = "HVc6oLrvbeikHCnedBheGtfomALKTziTibLMsBw6bbjk"
        const val BALANCE = 2_204_561_690L
        const val REVISION = 6

        const val DASHPAY = "Bwr4WHCPz5rFVAD87RqTs3izo4zpzwsEdKPWUT1NS1C7"
        const val GROUP = "4MjVAxDYjCdQqFBT48gzdvCsbVwFwtZzALEMWjns33pv"
        const val SINGLE = "D8benQGnycUTXbT8GRAaYf3GZMKVcWESiF9NyyW1Bi4e"

        val SECP = KeyType.ECDSA_SECP256K1
        val HASH160 = KeyType.ECDSA_HASH160
        val AUTH = Purpose.AUTHENTICATION
        val CONTACT_REQUEST = Bounds(SingleContractDocumentType::class.java, DASHPAY, "contactRequest")
        val PROFILE = Bounds(SingleContractDocumentType::class.java, DASHPAY, "profile")
        val CONTRACT_GROUP = Bounds(ContractGroupBounds::class.java, GROUP)
        val SINGLE_CONTRACT = Bounds(SingleContractBounds::class.java, SINGLE)
        val BUDGET_GROUP = BigInteger("50800000000")
        val BUDGET_PROFILE = BigInteger("200000000")

        // id, type, purpose, securityLevel, readOnly, disabledAt, bounds, version, totalBudget, expiresAt, data
        val EXPECTED_KEYS = listOf(
            ExpectedKey(
                0, SECP, AUTH, SecurityLevel.MASTER, false, null, null, 0, null, null,
                "037e828bf3c95a4d11851a9c17a96bed0d8c81553171d2651351444f5ec28f6cb6"
            ),
            ExpectedKey(
                1, SECP, AUTH, SecurityLevel.CRITICAL, false, null, null, 0, null, null,
                "0309c584ef50794ceafc7feddc66dde94e0ec36815ea56b5aff21b34c1d8563126"
            ),
            ExpectedKey(
                2, SECP, AUTH, SecurityLevel.HIGH, false, null, null, 0, null, null,
                "02b5626d38132a14cc70e24b87c5627931b3b648d9ae112919f4bb136e7ce61754"
            ),
            ExpectedKey(
                3, SECP, Purpose.TRANSFER, SecurityLevel.CRITICAL, false, null, null, 0, null, null,
                "02c461f161f9baba20d65b356b6250b4eda42ab4daea8c51b091eef4d53eee61f0"
            ),
            ExpectedKey(
                4, SECP, Purpose.ENCRYPTION, SecurityLevel.MEDIUM, false, null, CONTACT_REQUEST, 0, null, null,
                "039da92df49f91cdd32f77f72f7d2b180d11d72fe35d85042288b4d2c5ebedbfd4"
            ),
            ExpectedKey(
                5, SECP, Purpose.DECRYPTION, SecurityLevel.MEDIUM, false, null, CONTACT_REQUEST, 0, null, null,
                "024f23a04075825bded94c255df99afb9a919986c16d487348d31bdec6f3541f1b"
            ),
            ExpectedKey(
                6, HASH160, AUTH, SecurityLevel.CRITICAL, false, 1_794_258_718_034L, CONTRACT_GROUP, 1,
                BUDGET_GROUP, 1_802_034_428_073L, "ed755b795b3b8d7bb0dfd2e3ab309691d82b039f"
            ),
            ExpectedKey(
                7, HASH160, AUTH, SecurityLevel.HIGH, false, 1_794_258_718_034L, PROFILE, 1,
                BUDGET_PROFILE, 1_802_034_428_073L, "51731b3a9f33267172615d97ebf3976e3b8723a8"
            ),
            ExpectedKey(
                8, SECP, Purpose.ENCRYPTION, SecurityLevel.MEDIUM, false, null, SINGLE_CONTRACT, 0, null, null,
                "0343e192b5f6b7ab3d42797ed522502c6a147ca235ba3e56913ffe77c3f85ea29e"
            ),
            ExpectedKey(
                9, HASH160, AUTH, SecurityLevel.CRITICAL, false, 1_794_270_668_272L, CONTRACT_GROUP, 1,
                BUDGET_GROUP, 1_802_045_107_873L, "e14e24a9ee617abb4226575fd7052be7c13ff9c5"
            ),
            ExpectedKey(
                10, HASH160, AUTH, SecurityLevel.HIGH, false, 1_794_270_668_272L, PROFILE, 1,
                BUDGET_PROFILE, 1_802_045_107_873L, "96deb4077b2c9c5a0ffd7fcde7e9c4e05a94a668"
            ),
            ExpectedKey(
                11, HASH160, AUTH, SecurityLevel.CRITICAL, false, 1_794_270_961_175L, CONTRACT_GROUP, 1,
                BUDGET_GROUP, 1_802_046_751_030L, "a34a35130bda14728c6ab7c9f1729260e305aba8"
            ),
            ExpectedKey(
                12, HASH160, AUTH, SecurityLevel.HIGH, false, 1_794_270_961_175L, PROFILE, 1,
                BUDGET_PROFILE, 1_802_046_751_030L, "054db4097c09f88b1d79c73ad1ef23bb4e4eafef"
            )
        )
    }

    private fun fixture(): String {
        val stream = checkNotNull(javaClass.getResourceAsStream(FIXTURE)) { "$FIXTURE missing from test resources" }
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun identifier(base58: String) = Identifier.from(Base58.decode(base58))

    @Test
    fun realSdkResponseMapsToTheLegacyClientsViewOfTheSameIdentity() {
        val identity = SdkIdentityJson.parseIdentity(fixture()).toLegacyIdentity()

        assertEquals(identifier(IDENTITY_ID), identity.id)
        assertEquals(BALANCE, identity.balance)
        assertEquals(REVISION, identity.revision)
        assertEquals(EXPECTED_KEYS.size, identity.publicKeys.size)
        assertEquals(EXPECTED_KEYS.map { it.id }, identity.publicKeys.map { it.id })

        identity.publicKeys.zip(EXPECTED_KEYS).forEach { (actual, expected) ->
            val key = "key ${expected.id}"
            assertEquals("$key type", expected.type, actual.type)
            assertEquals("$key purpose", expected.purpose, actual.purpose)
            assertEquals("$key securityLevel", expected.securityLevel, actual.securityLevel)
            assertEquals("$key readOnly", expected.readOnly, actual.readOnly)
            assertEquals("$key disabledAt", expected.disabledAt, actual.disabledAt)
            assertEquals("$key version", expected.version, actual.version)
            assertEquals("$key totalBudget", expected.totalBudget, actual.totalBudget)
            assertEquals("$key expiresAt", expected.expiresAt, actual.expiresAt)
            assertArrayEquals("$key data", Utils.HEX.decode(expected.dataHex), actual.data)

            val bounds = actual.contractBounds
            val expectedBounds = expected.bounds
            if (expectedBounds == null) {
                assertNull("$key contractBounds", bounds)
            } else {
                assertEquals("$key contractBounds class", expectedBounds.type, bounds?.javaClass)
                assertEquals("$key contractBounds identifier", identifier(expectedBounds.identifier), bounds?.identifier)
                assertEquals(
                    "$key contractBounds documentType",
                    expectedBounds.documentType,
                    (bounds as? SingleContractDocumentType)?.documentType
                )
            }
        }
    }
}
