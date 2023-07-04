/*
 * Copyright 2023 Dash Core Group.
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

package org.dash.wallet.integration.uphold.api

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import io.jsonwebtoken.io.Decoders
import org.bitcoinj.core.Address
import org.spongycastle.asn1.ASN1Sequence
import org.spongycastle.asn1.pkcs.PrivateKeyInfo
import org.spongycastle.asn1.sec.ECPrivateKey
import org.spongycastle.asn1.x509.AlgorithmIdentifier
import org.spongycastle.asn1.x9.X9ObjectIdentifiers
import org.spongycastle.jce.provider.BouncyCastleProvider
import org.spongycastle.openssl.jcajce.JcaPEMKeyConverter
import java.util.Date
import java.util.UUID

class TopperClient {
    companion object {
        private const val BASE_URL = "https://app.topperpay.com/"
        private const val SANDBOX_URL = "https://app.sandbox.topperpay.com/"
    }

    private lateinit var keyId: String
    private lateinit var widgetId: String
    private lateinit var privateKey: String
    private var isSandbox: Boolean = false

    fun init(keyId: String, widgetId: String, privateKey: String, isSandbox: Boolean) {
        this.keyId = keyId
        this.widgetId = widgetId
        this.privateKey = privateKey
        this.isSandbox = isSandbox
    }

    fun getOnRampUrl(
        sourceAsset: String,
        sourceAmount: Double,
        receiverAddress: Address,
        walletName: String
    ): String {
        val token = generateToken(
            Decoders.BASE64.decode(privateKey),
            sourceAsset,
            sourceAmount,
            receiverAddress,
            walletName
        )

        return "${if (isSandbox) SANDBOX_URL else BASE_URL}?bt=$token"
    }

    private fun generateToken(
        privateKey: ByteArray,
        sourceAsset: String,
        sourceAmount: Double,
        receiverAddress: Address,
        walletName: String
    ): String {
        val seq = ASN1Sequence.getInstance(privateKey)
        val pKey = ECPrivateKey.getInstance(seq)
        val algId = AlgorithmIdentifier(X9ObjectIdentifiers.id_ecPublicKey, pKey.parameters)
        val key = JcaPEMKeyConverter().apply {
            setProvider(BouncyCastleProvider())
        }.getPrivateKey(PrivateKeyInfo(algId, pKey))

        return Jwts.builder()
            .setHeaderParam("kid", keyId)
            .setHeaderParam("typ", "JWT")
            .setId(UUID.randomUUID().toString())
            .setSubject(widgetId)
            .setIssuedAt(Date())
            .claim(
                "source",
                mapOf(
                    "asset" to sourceAsset,
                    "amount" to sourceAmount.toString()
                )
            )
            .claim(
                "target",
                mapOf(
                    "address" to receiverAddress.toString(),
                    "asset" to "DASH",
                    "network" to "dash",
                    "priority" to "fast",
                    "label" to walletName
                )
            )
            .signWith(key, SignatureAlgorithm.ES256)
            .compact()
    }
}
