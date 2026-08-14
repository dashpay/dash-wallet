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
import org.dash.wallet.common.money.MonetaryFormat
import org.dash.wallet.common.transactions.TxInfo
import org.dash.wallet.common.data.TaxCategory
import org.dash.wallet.common.data.entity.TransactionMetadata
import org.dash.wallet.common.services.TransactionMetadataProvider
import de.schildbach.wallet.transactions.TransactionUtils
import de.schildbach.wallet.transactions.TransactionUtils.isEntirelySelf
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*
import de.schildbach.wallet.util.format
import de.schildbach.wallet.util.setAmount
import de.schildbach.wallet.util.setFiatAmount
import de.schildbach.wallet.util.toDashjFiat
import de.schildbach.wallet.util.toDashjCoin
import de.schildbach.wallet.util.toNeutralCoin
import de.schildbach.wallet.util.toNeutralFiat
import de.schildbach.wallet.util.toTxId
import de.schildbach.wallet.util.toSha256Hash

@SuppressLint("SimpleDateFormat")
/**
 * Exports the wallet's transaction history.
 *
 * Takes an already-resolved [TxInfo] list rather than a dashj `Wallet`. Post-cutover the
 * SDK owns L1 and the dashj wallet is HELD — frozen at the cutover snapshot — so a wallet
 * restored after the cutover has no dashj transactions at all and `wallet.getTransactions()`
 * returned an empty list, producing a header-only CSV with no error (the field report that
 * prompted this). Callers pass `WalletDataProvider.getTransactions()`, which routes through
 * the cutover seam exactly like the transaction list the UI renders: the SDK-fed set
 * post-cutover, plus any dashj-only transactions the SDK store never learned, so history
 * can never shrink across the cutover.
 */
abstract class TransactionExporter(
    val transactionMetadataProvider: TransactionMetadataProvider,
    val transactions: Collection<TxInfo>,
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
        val emptyField: (TxInfo, TransactionMetadata?) -> String = { _, _ -> "" }

        /**
         * @return the date in iso8601 format as a string
         */
        val iso8601DateField: (TxInfo, TransactionMetadata?) -> String = { tx, metadata ->
            iso8601Format.format(Date(metadata?.timestamp ?: tx.updateTimeMillis))
        }
    }
    /** Keyed by lower-case txid hex — [TxInfo.txId]'s form. */
    protected lateinit var metadataMap: Map<String, TransactionMetadata>
    suspend fun initMetadataMap() = withContext(Dispatchers.IO) {
        val list = transactionMetadataProvider.getAllTransactionMetadata()

        metadataMap = if (list.isNotEmpty()) {
            list.associateBy({ it.txId.toString().lowercase() }, { it })
        } else {
            mapOf()
        }
    }

    /** exportString() implementations call this so exporting works without a prior initMetadataMap() call */
    protected suspend fun ensureMetadataMap() {
        if (!::metadataMap.isInitialized) {
            initMetadataMap()
        }
    }

    protected val sortedTransactions by lazy {
        transactions.sortedBy { it.updateTimeMillis }
    }

    /** Net value to the wallet; the SDK computes this post-cutover, dashj before it. */
    protected fun getTransactionValue(tx: TxInfo): Coin = Coin.valueOf(tx.netValueDuffs)

    protected fun isInternal(tx: TxInfo): Boolean = tx.isEntirelySelf

    /**
     * @return the tax category of the transaction, using [taxCategories]
     */
    val taxCategory: (TxInfo, TransactionMetadata?) -> String = { tx, metadata ->
        val taxCategory = metadata?.taxCategory
            ?: TaxCategory.getDefault(getTransactionValue(tx).isPositive, false)

        taxCategories[taxCategory.value]
    }

    /**
     * @return the cryptocurrency code of the transaction, which will be DASH
     */
    val currency: (TxInfo, TransactionMetadata?) -> String = { _, _ ->
        monetaryFormatCode
    }

    /**
     * @return the local currency code of the transaction
     */
    val fiatCurrency: (TxInfo, TransactionMetadata?) -> String = { tx, metadata ->
        // Only metadata carries the rate here: TxInfo is the neutral seam type and has no
        // exchange rate. No current dataSpec uses this column.
        metadata?.currencyCode ?: ""
    }

    /**
     * @return the value of the transaction if it was received, otherwise any empty string
     */
    val receivedValueOnly: (TxInfo, TransactionMetadata?) -> String = { tx, metadata ->
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
    val sentValueOnly: (TxInfo, TransactionMetadata?) -> String = { tx, metadata ->
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
    val value: (TxInfo, TransactionMetadata?) -> String = { tx, metadata ->
        val value = getTransactionValue(tx)
        value.toString()
    }

    /**
     * @return the transaction id in hex format
     */
    val transactionId: (TxInfo, TransactionMetadata?) -> String = { tx, _ ->
        tx.txId
    }

    /**
     * @return the source string "DASH Wallet"
     */
    val sourceDashWallet: (TxInfo, TransactionMetadata?) -> String = { _, _ ->
        "DASH Wallet"
    }

    abstract suspend fun exportString(): String
}