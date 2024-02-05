/*
 * Copyright 2022 Dash Core Group.
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

package de.schildbach.wallet.ui.transactions

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import de.schildbach.wallet.ui.main.HistoryRowView
import de.schildbach.wallet_test.R
import org.bitcoinj.core.*
import org.bitcoinj.utils.ExchangeRate
import org.dash.wallet.common.data.PresentableTxMetadata
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.transactions.TransactionUtils.isEntirelySelf
import org.dash.wallet.common.transactions.TransactionWrapper
import org.dash.wallet.common.util.ResourceString
import org.dash.wallet.integrations.crowdnode.transactions.FullCrowdNodeSignUpTxSet

data class TransactionRowView(
    override val title: ResourceString?,
    val txId: Sha256Hash,
    val value: Coin,
    val exchangeRate: ExchangeRate?,
    @DrawableRes val icon: Int,
    val iconBitmap: Bitmap?,
    @StyleRes val iconBackground: Int?,
    @StringRes val statusRes: Int,
    val transactionAmount: Int,
    val time: Long,
    val timeFormat: Int,
    val hasErrors: Boolean,
    val service: String?,
    val txWrapper: TransactionWrapper?
): HistoryRowView() {
    companion object {
        fun fromTransactionWrapper(
            txWrapper: TransactionWrapper,
            bag: TransactionBag,
            context: Context,
            metadata: PresentableTxMetadata? = null
        ): TransactionRowView {
            val lastTx = txWrapper.transactions.last()

            return if (txWrapper is FullCrowdNodeSignUpTxSet) {
                TransactionRowView(
                    ResourceString(R.string.crowdnode_account),
                    lastTx.txId,
                    txWrapper.getValue(bag),
                    lastTx.exchangeRate,
                    R.drawable.ic_crowdnode_logo,
                    null,
                    R.style.TxNoBackground,
                    -1,
                    txWrapper.transactions.size,
                    lastTx.updateTime.time,
                    TxResourceMapper().dateTimeFormat,
                    false,
                    ServiceName.CrowdNode,
                    txWrapper
                )
            } else {
                fromTransaction(lastTx, bag, context, metadata)
            }
        }

        fun fromTransaction(
            tx: Transaction,
            bag: TransactionBag,
            context: Context,
            metadata: PresentableTxMetadata? = null,
            resourceMapper: TxResourceMapper = TxResourceMapper()
        ): TransactionRowView {
            val value = tx.getValue(bag)
            val isInternal = tx.isEntirelySelf(bag)
            val isSent = value.signum() < 0
            val removeFee = isSent && tx.fee != null && !tx.fee.isZero
            @DrawableRes val icon: Int
            @StyleRes val iconBackground: Int
            var title = ResourceString(resourceMapper.getTransactionTypeName(tx, bag))
            val hasErrors = tx.confidence.hasErrors()

            if (hasErrors) {
                icon = R.drawable.ic_transaction_failed
                iconBackground = R.style.TxErrorBackground
                title = ResourceString(resourceMapper.getErrorName(tx))
            } else if (metadata?.service == ServiceName.CTXSpend) {
                icon = R.drawable.ic_gift_card_tx
                iconBackground = R.style.TxOrangeBackground
                title = ResourceString(
                    if (metadata.title.isNullOrEmpty()) {
                        R.string.explore_payment_gift_card
                    } else {
                        R.string.gift_card_tx_title
                    },
                    listOf(metadata.title ?: "")
                )
            } else if (isInternal) {
                icon = R.drawable.ic_internal
                iconBackground = R.style.TxSentBackground
            } else if (isSent) {
                icon = R.drawable.ic_transaction_sent
                iconBackground = R.style.TxSentBackground
            } else {
                icon = R.drawable.ic_transaction_received
                iconBackground = R.style.TxReceivedBackground
            }

            val status = if (!hasErrors && !isSent) {
                resourceMapper.getReceivedStatusString(tx, context)
            } else {
                -1
            }

            return TransactionRowView(
                title,
                tx.txId,
                if (removeFee) value.add(tx.fee) else value,
                tx.exchangeRate,
                icon,
                metadata?.icon,
                iconBackground,
                status,
                1,
                tx.updateTime.time,
                resourceMapper.dateTimeFormat,
                hasErrors,
                metadata?.service,
                null
            )
        }
    }
}
