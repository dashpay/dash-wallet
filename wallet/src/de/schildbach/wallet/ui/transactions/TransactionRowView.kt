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
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.data.PresentableTxMetadata
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.transactions.TransactionComparator
import org.dash.wallet.common.transactions.TransactionUtils.isEntirelySelf
import org.dash.wallet.common.transactions.TransactionWrapper
import org.dash.wallet.common.transactions.TransactionWrapperComparator
import org.dash.wallet.common.util.ResourceString
import org.dash.wallet.integrations.crowdnode.transactions.FullCrowdNodeSignUpTxSet
import org.slf4j.LoggerFactory

class TransactionRowViewComparator(private val wallet: Wallet): Comparator<TransactionRowView> {
    private val txComparator = TransactionComparator()

    override fun compare(row1: TransactionRowView, row2: TransactionRowView): Int {
        val tx1 = row1.txWrapper?.transactions?.last() ?: wallet.getTransaction(row1.txId)!!
        val tx2 = row2.txWrapper?.transactions?.last() ?: wallet.getTransaction(row2.txId)!!
        return txComparator.compare(
            tx1,
            tx2
        )
    }
}

data class TransactionRowView(
    override var title: ResourceString?,
    val txId: Sha256Hash,
    val value: Coin,
    val exchangeRate: ExchangeRate?,
    val contact: DashPayProfile?,
    @DrawableRes val icon: Int,
    val iconBitmap: Bitmap?,
    @StyleRes val iconBackground: Int?,
    @StringRes var statusRes: Int,
    val comment: String,
    val transactionAmount: Int,
    var time: Long,
    val timeFormat: Int,
    val hasErrors: Boolean,
    val service: String?,
    val txWrapper: TransactionWrapper?
): HistoryRowView() {
    companion object {
        private val log = LoggerFactory.getLogger(TransactionRowView::class.java)
        fun fromTransactionWrapper(
            txWrapper: TransactionWrapper,
            bag: TransactionBag,
            context: Context,
            contact: DashPayProfile?,
            metadata: PresentableTxMetadata? = null
        ): TransactionRowView {
            val lastTx = txWrapper.transactions.last()

            return when (txWrapper) {
                is FullCrowdNodeSignUpTxSet -> TransactionRowView(
                        ResourceString(R.string.crowdnode_account),
                        lastTx.txId,
                        txWrapper.getValue(bag),
                        lastTx.exchangeRate,
                        null,
                        R.drawable.ic_crowdnode_logo,
                        null,
                        R.style.TxNoBackground,
                        -1,
                        metadata?.memo ?: "",
                        txWrapper.transactions.size,
                        lastTx.updateTime.time,
                        TxResourceMapper().dateTimeFormat,
                        false,
                        ServiceName.CrowdNode,
                        txWrapper
                    )

                is CoinJoinMixingTxSet -> TransactionRowView(
                    ResourceString(R.string.coinjoin_mixing_transactions),
                    lastTx.txId,
                    txWrapper.getValue(bag),
                    lastTx.exchangeRate,
                    null,
                    R.drawable.ic_coinjoin_mixing_group,
                    null,
                    R.style.TxNoBackground,
                    -1,
                    metadata?.memo ?: "",
                    txWrapper.transactions.size,
                    lastTx.updateTime.time,
                    TxResourceMapper().dateTimeFormat,
                    false,
                    ServiceName.Unknown,
                    txWrapper
                )

                else -> fromTransaction(lastTx, bag, context, metadata, contact)
            }
        }

        fun fromTransaction(
            tx: Transaction,
            bag: TransactionBag,
            context: Context,
            metadata: PresentableTxMetadata? = null,
            contact: DashPayProfile? = null,
            resourceMapper: TxResourceMapper = TxResourceMapper()
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
            } else if (metadata?.service == ServiceName.DashDirect) {
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
    fun update(
        tx: Transaction,
        bag: TransactionBag,
        context: Context,
        resourceMapper: TxResourceMapper = TxResourceMapper()) {
        if (txId == tx.txId) {
            log.info("matches txId: {}", txId)
            log.info("previous title, {}, {}, {}", title?.resourceId, title?.args, txId)
            title = ResourceString(resourceMapper.getTransactionTypeName(tx, bag))
            log.info("setting title, {}, {}, {}", title?.resourceId, title?.args, txId)
            val isSent = value.signum() < 0
            log.info("previous status, {}, {}", statusRes, txId)
            statusRes = if (!hasErrors && !isSent) {
                resourceMapper.getReceivedStatusString(tx, context)
            } else {
                -1
            }
            log.info("setting status, {}, {}", statusRes, txId)
            // should we update this?
            // time = tx.updateTime.time
        } else if (txWrapper?.transactions?.find { it.txId == tx.txId } !== null) {
            // not sure what to do, maybe nothing
        }
    }
}
