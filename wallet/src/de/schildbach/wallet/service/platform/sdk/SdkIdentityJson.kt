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

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import org.bitcoinj.core.Base58
import org.dashj.platform.dpp.ProtocolVersion
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.dpp.identity.ContractBounds
import org.dashj.platform.dpp.identity.ContractGroupBounds
import org.dashj.platform.dpp.identity.Identity
import org.dashj.platform.dpp.identity.IdentityPublicKey
import org.dashj.platform.dpp.identity.SingleContractBounds
import org.dashj.platform.dpp.identity.SingleContractDocumentType
import org.dashj.platform.sdk.KeyType
import org.dashj.platform.sdk.Purpose
import org.dashj.platform.sdk.SecurityLevel
import java.io.IOException
import java.io.StringReader
import java.math.BigInteger
import java.util.Base64

/** Identity JSON from the Kotlin SDK that does not have the shape [SdkIdentityJson] reads. */
class SdkIdentityJsonException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * An identity as the Kotlin SDK's identity queries return it, parsed but not yet turned into the
 * legacy model. Enum fields stay numeric here: building the legacy [Identity] touches the SWIG
 * enums [KeyType], [Purpose] and [SecurityLevel], whose static initialisers call native getters in
 * `libsdklib`, so [toLegacyIdentity] must only run once the legacy `Platform` client has loaded
 * that library. Parsing (and every parse failure) never touches those classes.
 */
data class SdkIdentityRecord(
    val id: Identifier,
    /** Sorted by key id, as the legacy model reads them from the native identity's key map. */
    val publicKeys: List<SdkIdentityKeyRecord>,
    /** Credits. */
    val balance: Long,
    val revision: Int
) {
    /**
     * The legacy model, field for field what `Identity(RustIdentity)` builds from the same
     * identity: keys in key-id order, the protocol version the legacy constructor defaults to
     * ([ProtocolVersion.latestVersion]), and no asset lock proof or metadata.
     *
     * @throws IllegalArgumentException for a key type, purpose or security level the legacy
     *   enums do not define
     */
    fun toLegacyIdentity(): Identity = Identity(
        id,
        publicKeys.map { it.toLegacyKey() },
        balance,
        revision,
        ProtocolVersion.latestVersion
    )
}

data class SdkIdentityKeyRecord(
    val id: Int,
    val type: Int,
    val purpose: Int,
    val securityLevel: Int,
    val contractBounds: SdkContractBounds?,
    val data: ByteArray,
    val readOnly: Boolean,
    val disabledAt: Long?,
    /** 0 or 1: a version-1 key may carry [totalBudget] (credits) and [expiresAt]. */
    val version: Int,
    val totalBudget: BigInteger?,
    val expiresAt: Long?
) {
    fun toLegacyKey(): IdentityPublicKey = IdentityPublicKey(
        id,
        KeyType.swigToEnum(type),
        Purpose.swigToEnum(purpose),
        SecurityLevel.swigToEnum(securityLevel),
        contractBounds?.toLegacy(),
        data,
        readOnly,
        disabledAt = disabledAt,
        totalBudget = totalBudget,
        expiresAt = expiresAt,
        version = version
    )

    override fun equals(other: Any?): Boolean = other is SdkIdentityKeyRecord &&
        id == other.id && type == other.type && purpose == other.purpose &&
        securityLevel == other.securityLevel && contractBounds == other.contractBounds &&
        data.contentEquals(other.data) && readOnly == other.readOnly && disabledAt == other.disabledAt &&
        version == other.version && totalBudget == other.totalBudget && expiresAt == other.expiresAt

    override fun hashCode(): Int = 31 * id + data.contentHashCode()
}

sealed class SdkContractBounds {
    abstract val id: Identifier
    data class SingleContract(override val id: Identifier) : SdkContractBounds()
    data class DocumentType(override val id: Identifier, val documentTypeName: String) : SdkContractBounds()
    data class ContractGroup(override val id: Identifier) : SdkContractBounds()

    fun toLegacy(): ContractBounds = when (this) {
        is SingleContract -> SingleContractBounds(id)
        is DocumentType -> SingleContractDocumentType(id, documentTypeName)
        is ContractGroup -> ContractGroupBounds(id)
    }
}

/**
 * Reads the identity JSON the pinned Kotlin SDK returns from `identities.fetchByPublicKeyHash`
 * (one identity) and `identities.fetchByNonUniquePublicKeyHash` (an array of at most one).
 *
 * The shape is rs-dpp's serde output through `serde_json::to_string(&Identity)` in
 * rs-sdk-ffi (`identity/queries/by_public_key_hash.rs`), verified against the dashpay/platform
 * sources of the `5.1.0-int36` artifact:
 * ```
 * {"$formatVersion":"0","id":"<base58>","publicKeys":[<key>...],"balance":<u64>,"revision":<u64>}
 * key: {"$formatVersion":"0"|"1","id":<u32>,"purpose":<u8>,"securityLevel":<u8>,
 *       "contractBounds":null|{"$type":"singleContract","id":"<base58>"}
 *                        |{"$type":"documentType","id":"<base58>","documentTypeName":"<name>"}
 *                        |{"$type":"contractGroup","id":"<base58>"},
 *       "type":<u8>,"readOnly":<bool>,"data":"<base64>",
 *       "disabledAt":<u64>        (omitted when not disabled)
 *       "totalBudget":<u64>, "expiresAt":<u64>   (version 1 only, each omitted when absent)}
 * ```
 * `publicKeys` is a list (serialized from the identity's key map in key-id order). The enums are
 * their numeric `repr(u8)` values. `disabledAt`, `totalBudget` and `expiresAt` use rs-dpp's
 * JSON-safe integers: a number up to 2^53 - 1, a decimal string above it; both are accepted.
 *
 * Anything else throws [SdkIdentityJsonException]: malformed JSON, an unknown format version or
 * bounds type, a missing or mistyped field, a value out of range, or duplicate key ids. Nothing
 * partial is returned.
 */
object SdkIdentityJson {
    private val elementAdapter = Gson().getAdapter(JsonElement::class.java)

    /** Parses one identity object. */
    fun parseIdentity(text: String): SdkIdentityRecord = parseIdentity(parseElement(text))

    /** Parses an array of identity objects (the non-unique key-hash query's page). */
    fun parseIdentityArray(text: String): List<SdkIdentityRecord> {
        val array = parseElement(text) as? JsonArray ?: fail("identity list is not a JSON array")
        return array.map { parseIdentity(it) }
    }

    fun parseIdentity(element: JsonElement): SdkIdentityRecord {
        val obj = element as? JsonObject ?: fail("identity is not a JSON object")
        val format = obj.string("\$formatVersion")
        if (format != "0") fail("unsupported identity format version $format")
        val keysElement = obj.get("publicKeys") as? JsonArray ?: fail("identity publicKeys is not a JSON array")
        val keys = keysElement.map { parseKey(it) }
        if (keys.map { it.id }.toSet().size != keys.size) fail("identity has duplicate key ids")
        val revision = obj.unsignedNumber("revision")
        if (revision > BigInteger.valueOf(Int.MAX_VALUE.toLong())) fail("identity revision out of range")
        return SdkIdentityRecord(
            id = obj.identifier("id"),
            publicKeys = keys.sortedBy { it.id },
            balance = obj.unsignedNumber("balance").longValueOrFail("balance"),
            revision = revision.toInt()
        )
    }

    private fun parseKey(element: JsonElement): SdkIdentityKeyRecord {
        val obj = element as? JsonObject ?: fail("identity key is not a JSON object")
        val version = when (val format = obj.string("\$formatVersion")) {
            "0" -> 0
            "1" -> 1
            else -> fail("unsupported identity key format version $format")
        }
        val totalBudget = if (version == 1) obj.optionalJsonSafeUnsigned("totalBudget") else null
        val expiresAt = if (version == 1) obj.optionalJsonSafeUnsigned("expiresAt")?.longValueOrFail("expiresAt") else null
        if (version == 0 && (obj.has("totalBudget") || obj.has("expiresAt"))) {
            fail("identity key limits require format version 1")
        }
        val id = obj.unsignedNumber("id")
        if (id > BigInteger.valueOf(Int.MAX_VALUE.toLong())) fail("identity key id out of range")
        return SdkIdentityKeyRecord(
            id = id.toInt(),
            type = obj.enumValue("type"),
            purpose = obj.enumValue("purpose"),
            securityLevel = obj.enumValue("securityLevel"),
            contractBounds = parseBounds(obj.get("contractBounds")),
            data = obj.base64("data"),
            readOnly = obj.boolean("readOnly"),
            disabledAt = obj.optionalJsonSafeUnsigned("disabledAt")?.longValueOrFail("disabledAt"),
            version = version,
            totalBudget = totalBudget,
            expiresAt = expiresAt
        )
    }

    private fun parseBounds(element: JsonElement?): SdkContractBounds? {
        if (element == null || element is JsonNull) return null
        val obj = element as? JsonObject ?: fail("identity key contractBounds is not a JSON object")
        val id = obj.identifier("id")
        return when (val type = obj.string("\$type")) {
            "singleContract" -> SdkContractBounds.SingleContract(id)
            "documentType" -> SdkContractBounds.DocumentType(id, obj.string("documentTypeName"))
            "contractGroup" -> SdkContractBounds.ContractGroup(id)
            else -> fail("unknown contract bounds type $type")
        }
    }

    /** Strict parse of one complete JSON document (Gson's `JsonParser` would read leniently). */
    private fun parseElement(text: String): JsonElement = try {
        val reader = JsonReader(StringReader(text))
        reader.isLenient = false
        val element = elementAdapter.read(reader)
        if (reader.peek() != JsonToken.END_DOCUMENT) fail("identity JSON has trailing content")
        element
    } catch (e: IOException) {
        throw SdkIdentityJsonException("identity JSON is malformed", e)
    } catch (e: IllegalStateException) {
        throw SdkIdentityJsonException("identity JSON is malformed", e)
    } catch (e: com.google.gson.JsonParseException) {
        throw SdkIdentityJsonException("identity JSON is malformed", e)
    }

    private fun fail(message: String): Nothing = throw SdkIdentityJsonException(message)

    private fun JsonObject.primitive(name: String): JsonPrimitive =
        get(name) as? JsonPrimitive ?: fail("identity field $name is missing or not a JSON value")

    private fun JsonObject.string(name: String): String {
        val value = primitive(name)
        if (!value.isString) fail("identity field $name is not a string")
        return value.asString
    }

    private fun JsonObject.boolean(name: String): Boolean {
        val value = primitive(name)
        if (!value.isBoolean) fail("identity field $name is not a boolean")
        return value.asBoolean
    }

    /** A JSON integer (not a string) >= 0. */
    private fun JsonObject.unsignedNumber(name: String): BigInteger {
        val value = primitive(name)
        if (!value.isNumber) fail("identity field $name is not a number")
        return value.asString.toUnsignedInteger(name)
    }

    /** rs-dpp's JSON-safe u64: a number up to 2^53 - 1, a decimal string above it; absent or null is null. */
    private fun JsonObject.optionalJsonSafeUnsigned(name: String): BigInteger? {
        val element = get(name) ?: return null
        if (element is JsonNull) return null
        val value = element as? JsonPrimitive ?: fail("identity field $name is not a number")
        if (!value.isNumber && !value.isString) fail("identity field $name is not a number")
        val parsed = value.asString.toUnsignedInteger(name)
        if (parsed.bitLength() > 64) fail("identity field $name exceeds 64 bits")
        return parsed
    }

    private fun JsonObject.enumValue(name: String): Int {
        val value = unsignedNumber(name)
        if (value > BigInteger.valueOf(255)) fail("identity field $name is not a u8")
        return value.toInt()
    }

    private fun JsonObject.identifier(name: String): Identifier {
        val bytes = try {
            Base58.decode(string(name))
        } catch (e: IllegalArgumentException) {
            throw SdkIdentityJsonException("identity field $name is not base58", e)
        }
        if (bytes.size != Identifier.IDENTIFIER_LENGTH) fail("identity field $name is not a 32-byte identifier")
        return Identifier.from(bytes)
    }

    private fun JsonObject.base64(name: String): ByteArray = try {
        Base64.getDecoder().decode(string(name))
    } catch (e: IllegalArgumentException) {
        throw SdkIdentityJsonException("identity field $name is not base64", e)
    }

    private fun String.toUnsignedInteger(name: String): BigInteger {
        if (isEmpty() || any { it !in '0'..'9' }) fail("identity field $name is not an unsigned integer")
        return BigInteger(this)
    }

    private fun BigInteger.longValueOrFail(name: String): Long {
        if (bitLength() > 63) fail("identity field $name exceeds the legacy model's range")
        return toLong()
    }
}
