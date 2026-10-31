/* Copyright 2026 Dash Core Group. SPDX-License-Identifier: GPL-3.0-or-later */
package de.schildbach.wallet.ui.more.connections.protocol

import java.math.BigInteger
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class AuthBinding(
    val kind: Int,
    val securityLevel: Int,
    val id: ByteArray,
    val totalBudget: BigInteger?,
    val expiresAt: Long?,
    val documentType: String? = null
)

data class EncryptionBinding(val contractId: ByteArray, val purpose: Int)

data class AppConnectRequest(
    val bytes: ByteArray,
    val identityHint: ByteArray?,
    val auth: List<AuthBinding>,
    val encryption: List<EncryptionBinding>,
    val connectionId: ByteArray?,
    val renew: Boolean,
    val createdAt: Long,
    val callback: URI?
) {
    fun requireFresh(now: Long) {
        require(createdAt >= now - 600_000 && createdAt <= now + 120_000) {
            "This connection QR has expired or its clock is incorrect. Scan a new QR."
        }
    }
}

/** Strict, Android-free decoder for the App Connect v2 profile. */
internal object AppConnectParser {
    fun query(raw: String, singleValued: Set<String> = setOf("n", "v", "cb")): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (part in raw.split('&')) {
            val pair = part.split('=', limit = 2)
            val key = URLDecoder.decode(pair[0], "UTF-8")
            val value = URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
            require(key !in singleValued || key !in result) { "Duplicate $key parameter" }
            result[key] = value
        }
        return result
    }

    fun parse(bytes: ByteArray, network: DashConnectNetwork, callback: String?): DashKeyRequest {
        require(bytes.size >= 67 && bytes[0] == 2.toByte()) { "Invalid v2 payload" }
        val pub = bytes.copyOfRange(1, 34)
        require(KeyExchangeCrypto.isValidCompressedPoint(pub)) { "Invalid ephemeral public key" }
        val app = bytes.copyOfRange(34, 66)
        val labelLength = bytes[66].toInt() and 255
        require(labelLength <= 64 && bytes.size >= 67 + labelLength) { "Invalid label length" }
        val label = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, 67, labelLength)).toString()
        require(label.codePoints().noneMatch { it in 0..31 || it in 127..159 || it == 0x2028 || it == 0x2029 ||
            Character.getType(it) == Character.FORMAT.toInt() }) {
            "Unsafe app label"
        }
        val auth = mutableListOf<AuthBinding>()
        val enc = mutableListOf<EncryptionBinding>()
        var hint: ByteArray? = null
        var connection: ByteArray? = null
        var renew = false
        var created: Long? = null
        var offset = 67 + labelLength
        var previous = -1
        val seen = mutableSetOf<Int>()
        while (offset < bytes.size) {
            require(offset + 2 <= bytes.size) { "Truncated TLV" }
            val type = bytes[offset++].toInt() and 255
            val length = bytes[offset++].toInt() and 255
            require(type >= previous && offset + length <= bytes.size) { "Invalid TLV order or length" }
            require(type !in 1..6 || type == 2 || type == 4 || type !in seen) { "Duplicate TLV" }
            previous = type
            seen.add(type)
            val value = bytes.copyOfRange(offset, offset + length)
            offset += length
            when (type) {
                1 -> { require(length == 32); hint = value }
                2 -> auth.add(authBinding(value))
                3 -> { require(length == 16); connection = value }
                4 -> {
                    require(length == 33 && value[32].toInt() in 1..2) { "Invalid encryption binding" }
                    enc.add(EncryptionBinding(value.copyOfRange(0, 32), value[32].toInt()))
                }
                5 -> { require(length == 1 && value[0] == 1.toByte()); renew = true }
                6 -> { require(length == 8); created = timestamp(value).also { require(it > 0) } }
                else -> require(type % 2 != 0) { "Unsupported mandatory TLV $type" }
            }
        }
        require(auth.size in 1..4 && enc.size <= 2 && created != null) { "Missing or excessive bindings/timestamp" }
        require(auth.map { "${it.kind}:${org.bitcoinj.core.Base58.encode(it.id)}:${it.documentType}" }.distinct().size == auth.size) {
            "Duplicate authentication bounds"
        }
        require(enc.map { "${org.bitcoinj.core.Base58.encode(it.contractId)}:${it.purpose}" }.distinct().size == enc.size) {
            "Duplicate encryption binding"
        }
        val returnUri = callback?.let {
            require(it.length <= 512) { "Callback too long" }
            URI(it).also { uri ->
                require(uri.scheme == "https" && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null) {
                    "Callback must be an absolute HTTPS URL without credentials"
                }
            }
        }
        return DashKeyRequest(pub, app, label, network, AppConnectRequest(
            bytes.copyOf(), hint, auth, enc, connection, renew, created, returnUri
        ))
    }

    private fun authBinding(value: ByteArray): AuthBinding {
        require(value.size >= 50) { "Truncated authentication binding" }
        val kind = value[0].toInt() and 255
        val level = value[1].toInt() and 255
        require(kind in 0..2) { "Unsupported binding kind" }
        require(level == 2 || (level == 1 && kind == 2)) { "Invalid security level" }
        val budget = BigInteger(1, value.copyOfRange(34, 42)).takeIf { it.signum() != 0 }
        require(kind != 2 || budget != null) { "Group bindings require a budget" }
        val expires = timestamp(value.copyOfRange(42, 50)).takeIf { it != 0L }
        val name = if (kind == 1) {
            require(value.size >= 51)
            val n = value[50].toInt() and 255
            require(n in 1..64 && value.size == 51 + n)
            String(value, 51, n, Charsets.US_ASCII).also {
                require(it.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid document type" }
            }
        } else { require(value.size == 50); null }
        return AuthBinding(kind, level, value.copyOfRange(2, 34), budget, expires, name)
    }

    private fun timestamp(bytes: ByteArray): Long = BigInteger(1, bytes).also {
        require(it <= BigInteger.valueOf(9_007_199_254_740_991L)) { "Timestamp exceeds safe integer range" }
    }.toLong()
}
