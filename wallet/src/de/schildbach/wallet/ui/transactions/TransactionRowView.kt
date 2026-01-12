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
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.transactions.coinjoin.CoinJoinMixingTxSet
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
import org.slf4j.LoggerFactory

data class TransactionRowView(
    override var title: ResourceString?,
    val id: String,
    val value: Coin,
    val exchangeRate: ExchangeRate?,
    val contact: DashPayProfile?,
    @DrawableRes val icon: Int,
    val iconBitmap: Bitmap?,
    @StyleRes val iconBackground: Int?,
    @StringRes var statusRes: Int,
    val comment: String,
    var transactionAmount: Int,
    var time: Long,
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
            contact: DashPayProfile?,
            metadata: PresentableTxMetadata? = null,
            chainLockBlockHeight: Int
        ): TransactionRowView {
            val firstTx = txWrapper.transactions.values.first()

            return when (txWrapper) {
                is FullCrowdNodeSignUpTxSet -> TransactionRowView(
                        ResourceString(R.string.crowdnode_account),
                        txWrapper.id,
                        txWrapper.getValue(bag),
                        firstTx.exchangeRate,
                        null,
                        R.drawable.ic_crowdnode_logo,
                        null,
                        R.style.TxNoBackground,
                        -1,
                        metadata?.memo ?: "",
                        txWrapper.transactions.size,
                        firstTx.updateTime.time,
                        TxResourceMapper().dateTimeFormat,
                        false,
                        ServiceName.CrowdNode,
                        txWrapper
                    )

                is CoinJoinMixingTxSet -> TransactionRowView(
                    ResourceString(R.string.coinjoin_mixing_transactions),
                    txWrapper.id,
                    txWrapper.getValue(bag),
                    firstTx.exchangeRate,
                    null,
                    R.drawable.ic_coinjoin_mixing_group,
                    null,
                    R.style.TxSentBackground,
                    -1,
                    metadata?.memo ?: "",
                    txWrapper.transactions.size,
                    firstTx.updateTime.time,
                    TxResourceMapper().dateTimeFormat,
                    false,
                    ServiceName.Unknown,
                    txWrapper
                )
                else -> fromTransaction(firstTx, bag, context, metadata, contact, chainLockBlockHeight = chainLockBlockHeight)
            }
        }

        fun fromTransaction(
            tx: Transaction,
            bag: TransactionBag,
            context: Context,
            metadata: PresentableTxMetadata? = null,
            contact: DashPayProfile? = null,
            resourceMapper: TxResourceMapper = TxResourceMapper(),
            chainLockBlockHeight: Int
        ): TransactionRowView {
            val value = tx.getValue(bag)
            val isInternal = tx.isEntirelySelf(bag)
            val isSent = value.signum() < 0
            val removeFee = isSent && tx.fee != null && !tx.fee.isZero
            @DrawableRes val icon: Int
            @StyleRes val iconBackground: Int
            var title = ResourceString(resourceMapper.getTransactionTypeName(tx, bag))
            val hasErrors = tx.getConfidence(context).hasErrors()

            if (hasErrors) {
                icon = R.drawable.ic_transaction_failed
                iconBackground = R.style.TxErrorBackground
                title = ResourceString(resourceMapper.getErrorName(tx))
            } else if (ServiceName.isDashSpend(metadata?.service)) {
                icon = R.drawable.ic_gift_card_tx
                iconBackground = R.style.TxOrangeBackground
                title = ResourceString(
                    if (metadata!!.title.isNullOrEmpty()) {
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
                resourceMapper.getReceivedStatusString(tx, context, chainLockBlockHeight)
            } else {
                -1
            }

            return TransactionRowView(
                title,
                tx.txId.toString(),
                if (removeFee) value.add(tx.fee) else value,
                tx.exchangeRate,
                contact,
                icon,
                metadata?.icon,
                iconBackground,
                status,
                metadata?.memo ?: "",
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
