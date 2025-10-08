/*
 * Copyright 2019 Dash Core Group.
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

import android.text.format.DateUtils
import android.util.Log
import androidx.annotation.StringRes
import org.bitcoinj.coinjoin.utils.CoinJoinTransactionType
import org.bitcoinj.core.Context
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionBag
import org.bitcoinj.core.TransactionConfidence
import org.bitcoinj.evolution.AssetLockTransaction
import org.bitcoinj.wallet.AuthenticationKeyChainGroup
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.authentication.AuthenticationGroupExtension
import de.schildbach.wallet.Constants
import de.schildbach.wallet_test.R
import org.bitcoinj.wallet.AuthenticationKeyChain
import org.dash.wallet.common.transactions.TransactionUtils.isEntirelySelf
import org.slf4j.LoggerFactory

open class TxResourceMapper {
    companion object {
        private val log = LoggerFactory.getLogger(TxResourceMapper::class.java)
    }
    open val dateTimeFormat: Int
        get() = DateUtils.FORMAT_SHOW_TIME

    /**
     * @param tx the transaction in question
     * @param bag the wallet that contains the transaction
     * @return resource id of the string that holds the name of the transaction type
     */
    @StringRes
    open fun getTransactionTypeName(tx: Transaction, bag: TransactionBag): Int {
        val typeId: Int

        if (tx.getValue(bag).signum() <= 0) {
            when (tx.type) {
                Transaction.Type.TRANSACTION_PROVIDER_REGISTER ->
                    typeId = R.string.transaction_row_status_masternode_registration
                Transaction.Type.TRANSACTION_PROVIDER_UPDATE_REGISTRAR ->
                    typeId = R.string.transaction_row_status_masternode_update_registrar
                Transaction.Type.TRANSACTION_PROVIDER_UPDATE_REVOKE ->
                    typeId = R.string.transaction_row_status_masternode_revoke
                Transaction.Type.TRANSACTION_PROVIDER_UPDATE_SERVICE ->
                    typeId = R.string.transaction_row_status_masternode_update_service
                else -> {
                    val confidence = tx.getConfidence(Constants.CONTEXT)
                    var dashPayWallet: Wallet? = null

                    if (bag is Wallet) {
                        dashPayWallet = bag
                    }

                    val coinJoinType = CoinJoinTransactionType.fromTx(tx, bag)

                    if (dashPayWallet != null && AssetLockTransaction.isAssetLockTransaction(tx)) {
                        val authExtension = dashPayWallet.getKeyChainExtension(AuthenticationGroupExtension.EXTENSION_ID) as AuthenticationGroupExtension
                        val cftx = authExtension.getAssetLockTransaction(tx)

                        typeId = if (cftx != null && cftx.assetLockPublicKeyId != null) {
                            val group = authExtension.keyChainGroup as AuthenticationKeyChainGroup
                            when (group.getKeyChainType(cftx.assetLockPublicKeyId.bytes)) {
                                AuthenticationKeyChain.KeyChainType.INVITATION_FUNDING -> R.string.dashpay_invite_fee
                                AuthenticationKeyChain.KeyChainType.BLOCKCHAIN_IDENTITY_FUNDING -> R.string.dashpay_upgrade_fee
                                AuthenticationKeyChain.KeyChainType.BLOCKCHAIN_IDENTITY_TOPUP -> R.string.dashpay_topup_fee
                                else -> R.string.transaction_row_status_sent
                            }
                        } else {
                            log.info("transaction marked as asset lock, but not found (yet?)")
                            R.string.transaction_row_status_sent
                        }
                    } else if (coinJoinType == CoinJoinTransactionType.Mixing) {
                        typeId = R.string.transaction_row_status_coinjoin_mixing
                    } else if (confidence.hasErrors()) {
                        typeId = R.string.transaction_row_status_error_sending
                    } else if (tx.isEntirelySelf(bag)) {
                        // internal transactions could be CoinJoin related transactions
                        typeId = when (coinJoinType) {
                            CoinJoinTransactionType.CreateDenomination -> R.string.transaction_row_status_coinjoin_create_denominations
                            CoinJoinTransactionType.MakeCollateralInputs -> R.string.transaction_row_status_coinjoin_make_collateral
                            CoinJoinTransactionType.MixingFee -> R.string.transaction_row_status_coinjoin_mixing_fee
                            CoinJoinTransactionType.CombineDust -> R.string.transaction_row_status_coinjoin_combine_dust
                            // if not any other type of internal transaction, then mark as "Internal"
                            else -> R.string.transaction_row_status_sent_internally
                        }
                    } else if (confidence.confidenceType == TransactionConfidence.ConfidenceType.BUILDING ||
                        (confidence.confidenceType == TransactionConfidence.ConfidenceType.PENDING &&
                                (confidence.numBroadcastPeers() >= 1 || confidence.ixType == TransactionConfidence.IXType.IX_LOCKED ||
                                        (confidence.peerCount == 1 && confidence.isSent)))) {
                        typeId = R.string.transaction_row_status_sent
                    } else {
                        typeId = R.string.transaction_row_status_sending
                    }
                }
            }
        } else {
            // Not all coinbase transactions are v3 transactions with type 5 (coinbase)
            typeId = if (tx.type == Transaction.Type.TRANSACTION_COINBASE || tx.isCoinBase) {
                // currently, we cannot tell if a coinbase transaction is a masternode mining reward
                R.string.transaction_row_status_mining_reward
            } else {
                R.string.transaction_row_status_received
            }
        }
        return typeId
    }

    /**
     * @param tx the transaction in question
     * @return the string id of the type of error or -1 no error name is known
     */
    @StringRes
    open fun getErrorName(tx: Transaction): Int {
        val error = TxError.fromTransaction(tx)
        return getErrorName(error)
    }

    @StringRes
    open fun getErrorName(error: TxError): Int {
        return when (error) {
            TxError.DoubleSpend -> R.string.transaction_row_status_error_dead
            TxError.InConflict -> R.string.transaction_row_status_error_conflicting
            TxError.Nonstandard -> R.string.transaction_row_status_error_non_standard
            TxError.Dust -> R.string.transaction_row_status_error_dust
            TxError.InsufficientFee -> R.string.transaction_row_status_error_insufficient_fee
            TxError.Duplicate -> R.string.transaction_row_status_error_duplicate
            TxError.Invalid -> R.string.transaction_row_status_error_invalid
            TxError.Malformed -> R.string.transaction_row_status_error_malformed
            TxError.Obsolete -> R.string.transaction_row_status_error_obsolete
            TxError.Unknown -> R.string.transaction_row_status_error_other
        }
    }

    /**
     * @return the secondary status or -1 if there is none
     */
    @StringRes
    open fun getReceivedStatusString(tx: Transaction, context: Context, bestChainLockBlockHeight: Int): Int {
        val confidence = tx.getConfidence(context)
        var statusId = -1
        if (confidence.confidenceType == TransactionConfidence.ConfidenceType.BUILDING) {
            val confirmations = confidence.depthInBlocks
            val isChainLocked = bestChainLockBlockHeight >= confidence.depthInBlocks

            // process coinbase transactions (Mining Rewards) before other BUILDING transactions
            if (tx.isCoinBase) {
                // coinbase transactions are locked if they have less than 100 confirmations
                if (confidence.depthInBlocks < Constants.NETWORK_PARAMETERS.spendableCoinbaseDepth) {
                    statusId = R.string.transaction_row_status_locked
                }
            } else if (confirmations < 6 && !isChainLocked && confidence.ixType != TransactionConfidence.IXType.IX_LOCKED) {
                // confirmations < 6
                // not ChainLocked
                // not InstantSendLocked
                statusId = R.string.transaction_row_status_confirming
            }
        } else if (confidence.confidenceType == TransactionConfidence.ConfidenceType.PENDING) {
            when (confidence.ixType) {
                TransactionConfidence.IXType.IX_LOCKED -> { }   // no status string for InstantSendLocked transactions
                TransactionConfidence.IXType.IX_REQUEST,        // received the InstantSendLock, but it has not been processed
                TransactionConfidence.IXType.IX_NONE,           // did not receive the InstantSendLock
                TransactionConfidence.IXType.IX_LOCK_FAILED ->  // received the InstantSendLock, but verification failed
                    statusId = R.string.transaction_row_status_processing
                else -> {}
            }
        }
        return statusId
    }

    /**
     * The Sending status is a transaction that has not been sent or has been sent
     * but there haven't been any peers announcing it nor does it have a verified
     * InstantSendLock.
     *
     * @param tx the transaction from which to get the isSending status
     * @param wallet the wallet to which the transaction belongs
     * @return true if the transaction is in a Sending status
     */
    open fun isSending(tx: Transaction, wallet: Wallet): Boolean {
        val value = tx.getValue(wallet)
        val confidence = tx.getConfidence(wallet.context)
        return !(value.isPositive ||
                (confidence.confidenceType == TransactionConfidence.ConfidenceType.BUILDING) ||
                (confidence.confidenceType == TransactionConfidence.ConfidenceType.PENDING &&
                        (confidence.numBroadcastPeers() > 0 || confidence.ixType != TransactionConfidence.IXType.IX_LOCKED)))
    }
} 