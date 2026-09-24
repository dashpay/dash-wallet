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
import kotlinx.coroutines.flow.first
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
    val createdAt: Long,
    /** This payment bought gift cards, so recovery must restore their metadata, not just a service. */
    val isGiftCardPurchase: Boolean = false,
    /** Merchant logo to restore with a recovered gift card purchase. */
    val merchantIconUrl: String? = null,
    /**
     * True once the payment has been judged never sent and its inputs released, leaving only the
     * removal of the records saved against it. Such a payment must never be locked or verified
     * again; it is kept solely so a failed cleanup can be retried.
     */
    val abandoned: Boolean = false,
    /**
     * True once active watching has stopped but the record is kept anyway, because it holds the
     * only durable copy of the selected provider. Such a payment is never polled; it is applied
     * only if the transaction turns up in the wallet by other means.
     */
    val watchExpired: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put(KEY_TX_ID, txId.toString())
        .put(KEY_TX, Utils.HEX.encode(txBytes))
        .put(KEY_PAYMENT_URL, paymentUrl)
        .put(KEY_SERVICE_NAME, serviceName ?: JSONObject.NULL)
        .put(KEY_CREATED_AT, createdAt)
        .put(KEY_GIFT_CARD, isGiftCardPurchase)
        .put(KEY_ICON_URL, merchantIconUrl ?: JSONObject.NULL)
        .put(KEY_ABANDONED, abandoned)
        .put(KEY_WATCH_EXPIRED, watchExpired)

    companion object {
        private const val KEY_TX_ID = "txId"
        private const val KEY_TX = "tx"
        private const val KEY_PAYMENT_URL = "paymentUrl"
        private const val KEY_SERVICE_NAME = "serviceName"
        private const val KEY_CREATED_AT = "createdAt"
        private const val KEY_ABANDONED = "abandoned"
        private const val KEY_GIFT_CARD = "giftCard"
        private const val KEY_ICON_URL = "iconUrl"
        private const val KEY_WATCH_EXPIRED = "watchExpired"

        fun fromJson(json: JSONObject): PendingDirectPayment = PendingDirectPayment(
            txId = Sha256Hash.wrap(json.getString(KEY_TX_ID)),
            txBytes = Utils.HEX.decode(json.getString(KEY_TX)),
            paymentUrl = json.getString(KEY_PAYMENT_URL),
            serviceName = if (json.isNull(KEY_SERVICE_NAME)) null else json.getString(KEY_SERVICE_NAME),
            createdAt = json.getLong(KEY_CREATED_AT),
            isGiftCardPurchase = json.optBoolean(KEY_GIFT_CARD, false),
            merchantIconUrl = if (json.isNull(KEY_ICON_URL)) null else json.optString(KEY_ICON_URL, "").ifEmpty { null },
            abandoned = json.optBoolean(KEY_ABANDONED, false),
            watchExpired = json.optBoolean(KEY_WATCH_EXPIRED, false)
        )
    }
}

@Singleton
// Persists BIP70 payments whose submission result is unknown, keyed by transaction id.
open class PendingDirectPaymentConfig @Inject constructor(
    private val appContext: Context,
    walletDataProvider: WalletDataProvider
) : BaseConfig(appContext, PREFERENCES_NAME, walletDataProvider) {
    companion object {
        const val PREFERENCES_NAME = "pending_direct_payments"
        val PENDING_PAYMENTS = stringPreferencesKey("pending_payments")
        private val log = LoggerFactory.getLogger(PendingDirectPaymentConfig::class.java)
    }

    private val mutex = Mutex()

    open suspend fun getAll(): List<PendingDirectPayment> = decode(get(PENDING_PAYMENTS)).readable

    /**
     * Reads every stored payment, refusing to guess when it cannot.
     *
     * [getAll] goes through [BaseConfig], whose reader turns an IOException into empty
     * preferences, and it drops entries it cannot decode. Both are reasonable defaults for a
     * settings store and wrong here: an unreadable file would report no pending payments, which
     * a caller deciding whether outpoints are safe to spend would read as nothing to protect.
     * This reads without that fallback and refuses to return a partial answer.
     *
     * @throws Exception if the store cannot be read or holds an entry that cannot be decoded
     */
    open suspend fun getAllOrThrow(): List<PendingDirectPayment> {
        val stored = decodeStrict()
        if (stored.unreadable.isNotEmpty()) {
            throw IllegalStateException(
                "${stored.unreadable.size} pending direct payment(s) could not be decoded"
            )
        }
        return stored.readable
    }

    // Both writes read strictly first. Reading through the fallback would turn a failed read into
    // an empty store, and the write would then replace the file with just this one entry,
    // discarding every other quarantine. Their locks live only in memory, so nothing would be
    // left for a restart to restore, which is the state the strict read exists to prevent. A
    // failing store aborts the write instead.
    open suspend fun add(payment: PendingDirectPayment) = mutex.withLock {
        val stored = decodeStrict()
        val payments = stored.readable.filter { it.txId != payment.txId } + payment
        set(PENDING_PAYMENTS, encode(payments, stored.unreadable))
    }

    open suspend fun remove(txId: Sha256Hash) = mutex.withLock {
        val stored = decodeStrict()
        val remaining = stored.readable.filter { it.txId != txId }
        if (remaining.size != stored.readable.size) {
            set(PENDING_PAYMENTS, encode(remaining, stored.unreadable))
        }
    }

    /** Reads without BaseConfig's IOException-to-empty fallback, so a failed read throws. */
    private suspend fun decodeStrict(): StoredPayments =
        decode(appContext.dataStore.data.first()[PENDING_PAYMENTS])

    /**
     * What was on disk: the entries we could read, and the raw entries we could not. Unreadable
     * entries are carried through every write rather than dropped, so a single bad record cannot
     * quietly delete a payment some later version might still make sense of.
     */
    private data class StoredPayments(
        val readable: List<PendingDirectPayment>,
        val unreadable: List<JSONObject>
    )

    private fun encode(payments: List<PendingDirectPayment>, unreadable: List<JSONObject>): String {
        val array = JSONArray()
        payments.forEach { array.put(it.toJson()) }
        unreadable.forEach { array.put(it) }
        return array.toString()
    }

    private fun decode(value: String?): StoredPayments {
        if (value.isNullOrEmpty()) {
            return StoredPayments(emptyList(), emptyList())
        }

        val array = try {
            JSONArray(value)
        } catch (e: JSONException) {
            log.error("pending direct payments are not a JSON array, discarding: {}", value, e)
            return StoredPayments(emptyList(), emptyList())
        }

        val readable = mutableListOf<PendingDirectPayment>()
        val unreadable = mutableListOf<JSONObject>()
        for (i in 0 until array.length()) {
            // Per entry: one malformed record used to return an empty list, so every other
            // pending payment stopped being restored and the next write erased them all.
            try {
                readable.add(PendingDirectPayment.fromJson(array.getJSONObject(i)))
            } catch (e: Exception) {
                // Deliberately broad: hex decoding throws a decoder exception derived from
                // IllegalStateException and Sha256Hash.wrap has its own runtime failures, so
                // naming types would let one entry escape and hide every payment again.
                log.error("could not read pending direct payment at index {}, keeping it as is", i, e)
                (array.opt(i) as? JSONObject)?.let { unreadable.add(it) }
            }
        }
        return StoredPayments(readable, unreadable)
    }
}
