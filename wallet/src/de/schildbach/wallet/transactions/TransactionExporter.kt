/*
 * Copyright (c) 2022. Dash Core Group.
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

package de.schildbach.wallet.transactions

import android.annotation.SuppressLint
import de.schildbach.wallet.util.WalletUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.utils.MonetaryFormat
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.data.TaxCategory
import org.dash.wallet.common.data.entity.TransactionMetadata
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.transactions.TransactionUtils
import org.dash.wallet.common.transactions.TransactionUtils.isEntirelySelf
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*

@SuppressLint("SimpleDateFormat")
abstract class TransactionExporter(
    val transactionMetadataProvider: TransactionMetadataProvider,
    val wallet: Wallet,
    val taxCategories: List<String>
) {

    val excludeInternal = true

    companion object {
        private val iso8601Format: DateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
        protected val monetaryFormatNoCode: MonetaryFormat = MonetaryFormat.BTC.noCode()
        protected val monetaryFormatCode: String = MonetaryFormat.BTC.code()

        init {
            val timeZone = TimeZone.getTimeZone("UTC")
            iso8601Format.timeZone = timeZone
        }

        /**
         * @return an empty column as a string ""
         */
        val emptyField: (Transaction, TransactionMetadata?) -> String = { _, _ -> "" }

        /**
         * @return the date in iso8601 format as a string
         */
        val iso8601DateField: (Transaction, TransactionMetadata?) -> String = { tx, metadata ->
            iso8601Format.format(metadata?.timestamp?.let { Date(it) }
                ?: WalletUtils.getTransactionDate(tx))
        }
    }
    protected lateinit var metadataMap: Map<Sha256Hash, TransactionMetadata>
    suspend fun initMetadataMap() = withContext(Dispatchers.IO) {
        val list = transactionMetadataProvider.getAllTransactionMetadata()

        metadataMap = if (list.isNotEmpty()) {
            list.associateBy({ it.txId }, { it })
        } else {
            mapOf()
        }
    }

    protected val sortedTransactions by lazy {
        wallet.getTransactions(false).sortedBy {
            val confidence = it.getConfidence(wallet.context)
            if (confidence != null && confidence.sentAt != null && confidence.sentAt < it.updateTime) {
                confidence.sentAt
            } else {
                it.updateTime
            }
        }
    }

    protected fun getTransactionValue(tx: Transaction): Coin {
        return tx.getValue(wallet)
    }

    protected fun isInternal(tx: Transaction) : Boolean {
        return tx.isEntirelySelf(wallet)
    }

    /**
     * @return the tax category of the transaction, using [taxCategories]
     */
    val taxCategory: (Transaction, TransactionMetadata?) -> String = { tx, metadata ->
        val taxCategory = metadata?.taxCategory
            ?: TaxCategory.getDefault(getTransactionValue(tx).isPositive, false)

        taxCategories[taxCategory.value]
    }

    /**
     * @return the cryptocurrency code of the transaction, which will be DASH
     */
    val currency: (Transaction, TransactionMetadata?) -> String = { _, _ ->
        monetaryFormatCode
    }

    /**
     * @return the local currency code of the transaction
     */
    val fiatCurrency: (Transaction, TransactionMetadata?) -> String = { tx, metadata ->
        val fiatCurrency = metadata?.currencyCode ?: tx.exchangeRate?.fiat?.currencyCode
        fiatCurrency ?: ""
    }

    /**
     * @return the value of the transaction if it was received, otherwise any empty string
     */
    val receivedValueOnly: (Transaction, TransactionMetadata?) -> String = { tx, metadata ->
        val value = getTransactionValue(tx)
        if(value.isPositive) {
            monetaryFormatNoCode.format(value).toString()
        } else {
            ""
        }
    }

    /**
     * @return the value of the transaction if it was sent, otherwise any empty string
     */
    val sentValueOnly: (Transaction, TransactionMetadata?) -> String = { tx, metadata ->
        val value = getTransactionValue(tx)
        if(value.isNegative) {
            monetaryFormatNoCode.format(value.negate()).toString()
        } else {
            ""
        }
    }

    /**
     * @return the value of the transaction. Positive for received, negative for sent
     */
    val value: (Transaction, TransactionMetadata?) -> String = { tx, metadata ->
        val value = getTransactionValue(tx)
        value.toString()
    }

    /**
     * @return the transaction id in hex format
     */
    val transactionId: (Transaction, TransactionMetadata?) -> String = { tx, _ ->
        tx.txId.toString()
    }

    /**
     * @return the source string "DASH Wallet"
     */
    val sourceDashWallet: (Transaction, TransactionMetadata?) -> String = { _, _ ->
        "DASH Wallet"
    }

    abstract fun exportString(): String
}