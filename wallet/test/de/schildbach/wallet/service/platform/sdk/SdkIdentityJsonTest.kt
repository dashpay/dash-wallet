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
import org.dashj.platform.dpp.ProtocolVersion
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.dpp.identity.ContractGroupBounds
import org.dashj.platform.dpp.identity.Identity
import org.dashj.platform.dpp.identity.IdentityPublicKey
import org.dashj.platform.dpp.identity.SingleContractBounds
import org.dashj.platform.dpp.identity.SingleContractDocumentType
import org.dashj.platform.sdk.KeyType
import org.dashj.platform.sdk.Purpose
import org.dashj.platform.sdk.SecurityLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigInteger
import java.util.Base64

/**
 * [SdkIdentityJson]: the identity JSON the pinned Kotlin SDK emits (rs-dpp serde through
 * rs-sdk-ffi, `5.1.0-int36`) maps to the same legacy [Identity] that `Identity(RustIdentity)` built
 * from the legacy client's native identity, and anything it cannot read throws.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    application = Application::class,
    sdk = [29],
    manifest = Config.NONE,
    shadows = [AppConnectKeyStateTest.ShadowSdkEnums::class]
)
class SdkIdentityJsonTest {

    private fun id(fill: Int) = ByteArray(32) { fill.toByte() }
    private fun b58(fill: Int) = Base58.encode(id(fill))
    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    private val identityId = id(7)
    private val dashPayContract = id(9)
    private val keyData = Array(6) { i -> ByteArray(33) { (i + 2).toByte() } }

    private fun keyJson(
        id: Int,
        type: Int = 0,
        purpose: Int = 0,
        level: Int = 0,
        bounds: String = "null",
        dataIndex: Int = id,
        readOnly: Boolean = false,
        extra: String = "",
        format: String = "0"
    ) = """{"${'$'}formatVersion":"$format","id":$id,"purpose":$purpose,"securityLevel":$level,""" +
        """"contractBounds":$bounds,"type":$type,"readOnly":$readOnly,"data":"${b64(keyData[dataIndex])}"$extra}"""

    private fun identityJson(keys: List<String>, balance: String = "123456789012", revision: String = "3") =
        """{"${'$'}formatVersion":"0","id":"${b58(7)}","publicKeys":[${keys.joinToString(",")}],""" +
            """"balance":$balance,"revision":$revision}"""

    /** The SDK-created six-key identity (RegistrationKeys): keys 4/5 bound to DashPay contactRequest. */
    private val sixKeyJson by lazy {
        val docBound = """{"${'$'}type":"documentType","id":"${b58(9)}","documentTypeName":"contactRequest"}"""
        identityJson(
            listOf(
                keyJson(0, level = 0),
                keyJson(1, level = 1),
                keyJson(2, level = 2),
                keyJson(3, purpose = 3, level = 1),
                keyJson(4, purpose = 1, level = 3, bounds = docBound),
                keyJson(5, purpose = 2, level = 3, bounds = docBound)
            )
        )
    }

    private fun legacyKey(
        id: Int,
        purpose: Purpose,
        level: SecurityLevel,
        bounds: org.dashj.platform.dpp.identity.ContractBounds? = null,
        disabledAt: Long? = null,
        type: KeyType = KeyType.ECDSA_SECP256K1,
        readOnly: Boolean = false,
        totalBudget: BigInteger? = null,
        expiresAt: Long? = null,
        version: Int = 0
    ) = IdentityPublicKey(
        id, type, purpose, level, bounds, keyData[id], readOnly,
        disabledAt = disabledAt, totalBudget = totalBudget, expiresAt = expiresAt, version = version
    )

    /** What the legacy `Identity(RustIdentity)` constructor builds: same fields, default protocol version. */
    private fun legacyIdentity(keys: List<IdentityPublicKey>, balance: Long = 123_456_789_012L, revision: Int = 3) =
        Identity(Identifier.from(identityId), keys, balance, revision, ProtocolVersion.latestVersion)

    private fun assertSameIdentity(expected: Identity, actual: Identity) {
        assertEquals(expected.toJSON(), actual.toJSON())
        assertEquals(expected.protocolVersion, actual.protocolVersion)
        assertEquals(expected.publicKeys.map { it.version }, actual.publicKeys.map { it.version })
        assertNull(actual.assetLockProof)
        assertNull(actual.metadata)
    }

    @Test
    fun keysWithoutContractBoundsMapFieldForField() {
        val json = identityJson(
            listOf(
                keyJson(0, level = 0),
                keyJson(1, level = 1, readOnly = true),
                keyJson(2, type = 2, purpose = 3, level = 1)
            )
        )

        val identity = SdkIdentityJson.parseIdentity(json).toLegacyIdentity()

        assertSameIdentity(
            legacyIdentity(
                listOf(
                    legacyKey(0, Purpose.AUTHENTICATION, SecurityLevel.MASTER),
                    legacyKey(1, Purpose.AUTHENTICATION, SecurityLevel.CRITICAL, readOnly = true),
                    legacyKey(2, Purpose.TRANSFER, SecurityLevel.CRITICAL, type = KeyType.ECDSA_HASH160)
                )
            ),
            identity
        )
        assertEquals(Identifier.from(identityId), identity.id)
        assertEquals(123_456_789_012L, identity.balance)
        assertEquals(3, identity.revision)
    }

    @Test
    fun contractBoundKeysKeepTheirBounds() {
        val identity = SdkIdentityJson.parseIdentity(sixKeyJson).toLegacyIdentity()

        val bound = SingleContractDocumentType(Identifier.from(dashPayContract), "contactRequest")
        assertSameIdentity(
            legacyIdentity(
                listOf(
                    legacyKey(0, Purpose.AUTHENTICATION, SecurityLevel.MASTER),
                    legacyKey(1, Purpose.AUTHENTICATION, SecurityLevel.CRITICAL),
                    legacyKey(2, Purpose.AUTHENTICATION, SecurityLevel.HIGH),
                    legacyKey(3, Purpose.TRANSFER, SecurityLevel.CRITICAL),
                    legacyKey(4, Purpose.ENCRYPTION, SecurityLevel.MEDIUM, bounds = bound),
                    legacyKey(5, Purpose.DECRYPTION, SecurityLevel.MEDIUM, bounds = bound)
                )
            ),
            identity
        )
        val encryption = checkNotNull(identity.getPublicKeyById(4))
        val bounds = encryption.contractBounds as SingleContractDocumentType
        assertEquals(Identifier.from(dashPayContract), bounds.identifier)
        assertEquals("contactRequest", bounds.documentType)
    }

    @Test
    fun singleContractAndContractGroupBoundsMap() {
        val json = identityJson(
            listOf(
                keyJson(0),
                keyJson(1, level = 2, bounds = """{"${'$'}type":"singleContract","id":"${b58(9)}"}"""),
                keyJson(2, level = 2, bounds = """{"${'$'}type":"contractGroup","id":"${b58(11)}"}""")
            )
        )

        val keys = SdkIdentityJson.parseIdentity(json).toLegacyIdentity().publicKeys

        assertNull(keys[0].contractBounds)
        assertEquals(SingleContractBounds::class.java, keys[1].contractBounds?.javaClass)
        assertEquals(Identifier.from(dashPayContract), keys[1].contractBounds?.identifier)
        assertEquals(ContractGroupBounds::class.java, keys[2].contractBounds?.javaClass)
        assertEquals(Identifier.from(id(11)), keys[2].contractBounds?.identifier)
    }

    @Test
    fun disabledKeyKeepsItsDisabledTime() {
        val json = identityJson(
            listOf(keyJson(0), keyJson(1, level = 1, extra = ""","disabledAt":1700000000000"""))
        )

        val identity = SdkIdentityJson.parseIdentity(json).toLegacyIdentity()

        assertSameIdentity(
            legacyIdentity(
                listOf(
                    legacyKey(0, Purpose.AUTHENTICATION, SecurityLevel.MASTER),
                    legacyKey(1, Purpose.AUTHENTICATION, SecurityLevel.CRITICAL, disabledAt = 1_700_000_000_000L)
                )
            ),
            identity
        )
        assertNull(identity.publicKeys[0].disabledAt)
        // A disabled key is never the "first" key of its purpose, as with the legacy fetch.
        assertEquals(0, identity.getFirstPublicKey(Purpose.AUTHENTICATION)?.id)
    }

    @Test
    fun versionOneKeyKeepsBudgetAndExpiryInEitherJsonSafeForm() {
        val json = identityJson(
            listOf(
                keyJson(0),
                // JSON-safe u64: a decimal string above 2^53 - 1
                keyJson(
                    1, level = 1, format = "1",
                    extra = ""","totalBudget":"18446744073709551615","expiresAt":1790000000000"""
                ),
                keyJson(2, level = 2, format = "1")
            )
        )

        val identity = SdkIdentityJson.parseIdentity(json).toLegacyIdentity()

        assertSameIdentity(
            legacyIdentity(
                listOf(
                    legacyKey(0, Purpose.AUTHENTICATION, SecurityLevel.MASTER),
                    legacyKey(
                        1, Purpose.AUTHENTICATION, SecurityLevel.CRITICAL,
                        totalBudget = BigInteger("18446744073709551615"), expiresAt = 1_790_000_000_000L, version = 1
                    ),
                    legacyKey(2, Purpose.AUTHENTICATION, SecurityLevel.HIGH, version = 1)
                )
            ),
            identity
        )
    }

    @Test
    fun keysComeOutInKeyIdOrderAsFromTheLegacyKeyMap() {
        val json = identityJson(listOf(keyJson(2, level = 2), keyJson(0), keyJson(1, level = 1)))
        assertEquals(listOf(0, 1, 2), SdkIdentityJson.parseIdentity(json).toLegacyIdentity().publicKeys.map { it.id })
    }

    @Test
    fun nonUniquePageParsesAsAList() {
        assertEquals(emptyList<SdkIdentityRecord>(), SdkIdentityJson.parseIdentityArray("[]"))
        val single = SdkIdentityJson.parseIdentityArray("[$sixKeyJson]")
        assertEquals(SdkIdentityJson.parseIdentity(sixKeyJson), single.single())
    }

    @Test
    fun unknownEnumValuesThrowWhenBuildingTheLegacyModel() {
        for (key in listOf(keyJson(0, type = 9), keyJson(0, purpose = 42), keyJson(0, level = 7))) {
            val record = SdkIdentityJson.parseIdentity(identityJson(listOf(key)))
            assertThrows(IllegalArgumentException::class.java) { record.toLegacyIdentity() }
        }
    }

    @Test
    fun unreadableJsonThrowsInsteadOfYieldingAPartialIdentity() {
        val bad = listOf(
            "",
            "not json",
            "$sixKeyJson trailing",
            "[$sixKeyJson]",
            sixKeyJson.replace("\"${'$'}formatVersion\":\"0\",\"id\":\"", "\"${'$'}formatVersion\":\"1\",\"id\":\""),
            identityJson(listOf(keyJson(0, format = "2"))),
            identityJson(listOf(keyJson(0, bounds = """{"${'$'}type":"somethingNew","id":"${b58(9)}"}"""))),
            identityJson(listOf(keyJson(0), keyJson(0, level = 1, dataIndex = 1))),
            identityJson(listOf(keyJson(0)), balance = "\"12\""),
            identityJson(listOf(keyJson(0)), balance = "-1"),
            identityJson(listOf(keyJson(0)), balance = "1.5"),
            identityJson(listOf(keyJson(0)), balance = "9223372036854775808"),
            identityJson(listOf(keyJson(0)), revision = "4294967296"),
            identityJson(listOf(keyJson(0, extra = ""","totalBudget":5"""))),
            identityJson(listOf(keyJson(0, extra = ""","disabledAt":"soon""""))),
            identityJson(listOf(keyJson(0, type = 256))),
            identityJson(listOf(keyJson(0))).replace("\"readOnly\":false", "\"readOnly\":\"false\""),
            identityJson(listOf(keyJson(0))).replace("\"publicKeys\":[", "\"publicKeys\":{\"x\":[").replace("],\"balance\"", "]},\"balance\""),
            identityJson(listOf(keyJson(0))).replace(b58(7), "0OIl"),
            identityJson(listOf(keyJson(0))).replace(b58(7), Base58.encode(ByteArray(20))),
            identityJson(listOf(keyJson(0))).replace(b64(keyData[0]), "not*base64"),
            identityJson(listOf(keyJson(0))).replace(",\"revision\":3", "")
        )
        for (json in bad) {
            assertThrows("should reject: $json", SdkIdentityJsonException::class.java) {
                SdkIdentityJson.parseIdentity(json)
            }
        }
        assertThrows(SdkIdentityJsonException::class.java) { SdkIdentityJson.parseIdentityArray(sixKeyJson) }
    }
}
