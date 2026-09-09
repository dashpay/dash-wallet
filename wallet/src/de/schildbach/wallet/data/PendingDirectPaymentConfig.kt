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

package de.schildbach.wallet.data

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Utils
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.BaseConfig
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A signed BIP70 payment whose HTTP submission failed ambiguously: the request may or may not
 * have reached the merchant, so the transaction may or may not have been broadcast.
 *
 * Kept on disk because dashj's output locks are in-memory only; on the next start the inputs are
 * re-locked and verification resumes (see PendingDirectPaymentVerifier).
 */
data class PendingDirectPayment(
    val txId: Sha256Hash,
    val txBytes: ByteArray,
    val paymentUrl: String,
    val serviceName: String?,
    val createdAt: Long
) {
    fun toJson(): JSONObject = JSONObject()
        .put(KEY_TX_ID, txId.toString())
        .put(KEY_TX, Utils.HEX.encode(txBytes))
        .put(KEY_PAYMENT_URL, paymentUrl)
        .put(KEY_SERVICE_NAME, serviceName ?: JSONObject.NULL)
        .put(KEY_CREATED_AT, createdAt)

    companion object {
        private const val KEY_TX_ID = "txId"
        private const val KEY_TX = "tx"
        private const val KEY_PAYMENT_URL = "paymentUrl"
        private const val KEY_SERVICE_NAME = "serviceName"
        private const val KEY_CREATED_AT = "createdAt"

        fun fromJson(json: JSONObject): PendingDirectPayment = PendingDirectPayment(
            txId = Sha256Hash.wrap(json.getString(KEY_TX_ID)),
            txBytes = Utils.HEX.decode(json.getString(KEY_TX)),
            paymentUrl = json.getString(KEY_PAYMENT_URL),
            serviceName = if (json.isNull(KEY_SERVICE_NAME)) null else json.getString(KEY_SERVICE_NAME),
            createdAt = json.getLong(KEY_CREATED_AT)
        )
    }
}

@Singleton
// Persists BIP70 payments whose submission result is unknown, keyed by transaction id.
open class PendingDirectPaymentConfig @Inject constructor(
    context: Context,
    walletDataProvider: WalletDataProvider
) : BaseConfig(context, PREFERENCES_NAME, walletDataProvider) {
    companion object {
        const val PREFERENCES_NAME = "pending_direct_payments"
        val PENDING_PAYMENTS = stringPreferencesKey("pending_payments")
        private val log = LoggerFactory.getLogger(PendingDirectPaymentConfig::class.java)
    }

    private val mutex = Mutex()

    open suspend fun getAll(): List<PendingDirectPayment> = decode(get(PENDING_PAYMENTS))

    open suspend fun add(payment: PendingDirectPayment) = mutex.withLock {
        val payments = getAll().filter { it.txId != payment.txId } + payment
        set(PENDING_PAYMENTS, encode(payments))
    }

    open suspend fun remove(txId: Sha256Hash) = mutex.withLock {
        val payments = getAll()
        val remaining = payments.filter { it.txId != txId }
        if (remaining.size != payments.size) {
            set(PENDING_PAYMENTS, encode(remaining))
        }
    }

    private fun encode(payments: List<PendingDirectPayment>): String {
        val array = JSONArray()
        payments.forEach { array.put(it.toJson()) }
        return array.toString()
    }

    private fun decode(value: String?): List<PendingDirectPayment> {
        if (value.isNullOrEmpty()) {
            return emptyList()
        }
        return try {
            val array = JSONArray(value)
            (0 until array.length()).map { PendingDirectPayment.fromJson(array.getJSONObject(it)) }
        } catch (e: JSONException) {
            log.error("could not parse pending direct payments, discarding: {}", value, e)
            emptyList()
        } catch (e: IllegalArgumentException) {
            log.error("could not parse pending direct payments, discarding: {}", value, e)
            emptyList()
        }
    }
}
