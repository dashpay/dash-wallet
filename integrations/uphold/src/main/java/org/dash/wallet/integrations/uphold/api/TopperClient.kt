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

package org.dash.wallet.integrations.uphold.api

import androidx.annotation.VisibleForTesting
import com.google.gson.Gson
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import io.jsonwebtoken.io.Decoders
import okhttp3.OkHttpClient
import org.bitcoinj.core.Address
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.get
import org.dash.wallet.integrations.uphold.data.SupportedTopperAssets
import org.dash.wallet.integrations.uphold.data.SupportedTopperPaymentMethods
import org.dash.wallet.integrations.uphold.data.TopperPaymentMethod
import org.slf4j.LoggerFactory
import org.spongycastle.asn1.ASN1Sequence
import org.spongycastle.asn1.pkcs.PrivateKeyInfo
import org.spongycastle.asn1.sec.ECPrivateKey
import org.spongycastle.asn1.x509.AlgorithmIdentifier
import org.spongycastle.asn1.x9.X9ObjectIdentifiers
import org.spongycastle.jce.provider.BouncyCastleProvider
import org.spongycastle.openssl.jcajce.JcaPEMKeyConverter
import java.lang.Exception
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Date
import java.util.SortedSet
import java.util.UUID
import javax.inject.Inject

class TopperClient @Inject constructor(
    private val httpClient: OkHttpClient
) {
    companion object {
        private const val DEFAULT_FIAT_AMOUNT = 100 // Target 100 USD, or similar in other currencies
        private const val BASE_URL = "https://app.topperpay.com/"
        private const val SANDBOX_URL = "https://app.sandbox.topperpay.com/"
        private const val SUPPORTED_ASSETS_URL = "https://api.topperpay.com/assets/crypto-onramp"
        const val SUPPORTED_PAYMENT_METHODS_URL = "https://api.topperpay.com/payment-methods/crypto-onramp"
        private val log = LoggerFactory.getLogger(TopperClient::class.java)
    }

    private lateinit var keyId: String
    private lateinit var widgetId: String
    private lateinit var privateKey: String
    private var isSandbox: Boolean = false
    private var supportedAssets: SortedSet<String> = sortedSetOf<String>()
    private var supportedPaymentMethods: List<TopperPaymentMethod> = listOf()

    val hasValidCredentials: Boolean
        get() = keyId.isNotEmpty() && widgetId.isNotEmpty() && privateKey.isNotEmpty()

    fun init(keyId: String, widgetId: String, privateKey: String, isSandbox: Boolean) {
        this.keyId = keyId
        this.widgetId = widgetId
        this.privateKey = privateKey
        this.isSandbox = isSandbox
    }

    fun getOnRampUrl(
        desiredSourceAsset: String,
        receiverAddress: Address,
        walletName: String
    ): String {
        val currency = if (isSupportedAsset(desiredSourceAsset)) {
            desiredSourceAsset
        } else {
            Constants.USD_CURRENCY
        }

        val token = generateToken(
            Decoders.BASE64.decode(privateKey),
            currency,
            receiverAddress,
            walletName
        )

        return "${if (isSandbox) SANDBOX_URL else BASE_URL}?bt=$token"
    }

    suspend fun refreshSupportedAssets() {
        supportedAssets = try {
            val response = httpClient.get(SUPPORTED_ASSETS_URL)
            val root = Gson().fromJson(response.body?.string(), SupportedTopperAssets::class.java)
            root.assets.source.map { it.code }.toSortedSet()
        } catch (ex: Exception) {
            log.error("Failed to get supported assets from Topper", ex)
            sortedSetOf()
        }
    }

    suspend fun refreshPaymentMethods() {
        supportedPaymentMethods = try {
            val response = httpClient.get(SUPPORTED_PAYMENT_METHODS_URL)
            val root = Gson().fromJson(response.body?.string(), SupportedTopperPaymentMethods::class.java)
            root.paymentMethods.filter { it.type == "credit-card" && it.billingAsset == "USD" }
        } catch (ex: Exception) {
            log.error("Failed to get supported assets from Topper", ex)
            listOf()
        }
    }

    private fun isSupportedAsset(asset: String): Boolean {
        return supportedAssets.contains(asset)
    }

    private fun generateToken(
        privateKey: ByteArray,
        sourceAsset: String,
        receiverAddress: Address,
        walletName: String
    ): String {
        val seq = ASN1Sequence.getInstance(privateKey)
        val pKey = ECPrivateKey.getInstance(seq)
        val algId = AlgorithmIdentifier(X9ObjectIdentifiers.id_ecPublicKey, pKey.parameters)
        val key = JcaPEMKeyConverter().apply {
            setProvider(BouncyCastleProvider())
        }.getPrivateKey(PrivateKeyInfo(algId, pKey))

        val defaultValue = getDefaultValue(sourceAsset, supportedPaymentMethods)
        // docs: https://docs.topperpay.com/flows/crypto-onramp
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
                    "amount" to defaultValue.toString()
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

    @VisibleForTesting
    fun getDefaultValue(sourceAsset: String, paymentMethods: List<TopperPaymentMethod>): Int {
        if (paymentMethods.isNotEmpty()) {
            val paymentMethod = paymentMethods.find { it.type == "credit-card" && it.billingAsset == "USD" }

            val minimumUSD = paymentMethod?.limits?.find {
                it.asset == "USD"
            }?.minimum?.toBigDecimal()
            val minimumMultplier = minimumUSD?.let { BigDecimal(DEFAULT_FIAT_AMOUNT).setScale(3) / minimumUSD }

            if (minimumMultplier != null) {
                val minimum = paymentMethod.limits.find { it.asset == sourceAsset }?.minimum?.toBigDecimal()
                // check if the minimum is greater than the default amount
                val amount = if (minimumMultplier > BigDecimal.ONE) {
                    minimum?.multiply(minimumMultplier)
                } else {
                    minimum?.round(MathContext(2, RoundingMode.CEILING))
                }
                if (minimum != null && amount != null) {
                    // This section will round the amount up
                    // 1. amounts below 100 will be rounded up with a precision of 1: 92 becomes 90
                    // 2. amounts above 100 will be rounded up with a precision of 2: 15070 becomes 15000
                    val precision = if (amount > BigDecimal.valueOf(100)) {
                        2
                    } else {
                        1
                    }
                    return amount.round(MathContext(precision, RoundingMode.HALF_UP)).toInt()
                }
            }
        }
        return DEFAULT_FIAT_AMOUNT
    }
}
