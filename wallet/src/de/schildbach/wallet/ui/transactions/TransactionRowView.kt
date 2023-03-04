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
import org.dash.wallet.common.transactions.TransactionUtils
import org.dash.wallet.common.transactions.TransactionUtils.isEntirelySelf
import org.dash.wallet.common.transactions.TransactionWrapper
import org.dash.wallet.integrations.crowdnode.transactions.FullCrowdNodeSignUpTxSet

data class TransactionRowView(
    val txId: Sha256Hash,
    val value: Coin,
    val exchangeRate: ExchangeRate?,
    @DrawableRes val icon: Int,
    val iconBitmap: Bitmap?,
    @StyleRes val iconBackground: Int?,
    @StringRes val titleRes: Int,
    @StringRes val statusRes: Int,
    val transactionAmount: Int,
    val time: Long,
    val timeFormat: Int,
    val hasErrors: Boolean,
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
                    lastTx.txId,
                    txWrapper.getValue(bag),
                    lastTx.exchangeRate,
                    R.drawable.ic_crowdnode_logo,
                    null,
                    R.style.TxNoBackground,
                    R.string.crowdnode_account,
                    -1,
                    txWrapper.transactions.size,
                    lastTx.updateTime.time,
                    TxResourceMapper().dateTimeFormat,
                    false,
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

            if (metadata?.service == ServiceName.DashDirect) {
                icon = R.drawable.ic_gift_card_tx
                iconBackground = R.style.TxTangerineBackground
            } else if (isInternal) {
                icon = R.drawable.ic_shuffle
                iconBackground = R.style.TxTangerineBackground
            } else if (isSent) {
                icon = R.drawable.ic_transaction_sent
                iconBackground = R.style.TxSentBackground
            } else {
                icon = R.drawable.ic_transaction_received
                iconBackground = R.style.TxReceivedBackground
            }

            val status = if (tx.confidence.hasErrors()) {
                resourceMapper.getErrorName(tx)
            } else if (!isSent) {
                resourceMapper.getReceivedStatusString(tx, context)
            } else {
                -1
            }

            return TransactionRowView(
                tx.txId,
                if (removeFee) value.add(tx.fee) else value,
                tx.exchangeRate,
                icon,
                metadata?.icon,
                iconBackground,
                resourceMapper.getTransactionTypeName(tx, bag),
                status,
                1,
                tx.updateTime.time,
                resourceMapper.dateTimeFormat,
                tx.confidence.hasErrors(),
                null
            )
        }
    }
}