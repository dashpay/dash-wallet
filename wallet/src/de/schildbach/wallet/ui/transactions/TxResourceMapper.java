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

package de.schildbach.wallet.ui.transactions;

import android.text.format.DateUtils;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import org.bitcoinj.coinjoin.CoinJoin;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionBag;
import org.bitcoinj.core.TransactionConfidence;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.evolution.CreditFundingTransaction;
import org.bitcoinj.wallet.AuthenticationKeyChainGroup;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.authentication.AuthenticationGroupExtension;
import org.dash.wallet.common.transactions.TransactionUtils;

import de.schildbach.wallet.Constants;
import de.schildbach.wallet_test.R;

public class TxResourceMapper {

    private boolean isCoinJoinMixingFee(Transaction tx) {
        Coin inputsValue = tx.getInputSum();
        Coin outputsValue = tx.getOutputSum();
        Coin netValue = inputsValue.subtract(outputsValue);
        return tx.getInputs().size() == 1 && tx.getOutputs().size() == 1
                && CoinJoin.isCollateralAmount(inputsValue)
                && CoinJoin.isCollateralAmount(outputsValue)
                && CoinJoin.isCollateralAmount(netValue);
    }
    /**
     *
     * @param tx the transaction in question
     * @param wallet the wallet that contains the transaction
     * @return resource id of the string that holds the name of the transaction type
     */
    @StringRes
    public int getTransactionTypeName(@NonNull Transaction tx, @NonNull TransactionBag wallet) {
        int typeId = 0;
        if(tx.getValue(wallet).signum() <= 0) {
            switch(tx.getType()) {
                case TRANSACTION_PROVIDER_REGISTER:
                    typeId = R.string.transaction_row_status_masternode_registration;
                    break;
                case TRANSACTION_PROVIDER_UPDATE_REGISTRAR:
                    typeId = R.string.transaction_row_status_masternode_update_registrar;
                    break;
                case TRANSACTION_PROVIDER_UPDATE_REVOKE:
                    typeId = R.string.transaction_row_status_masternode_revoke;
                    break;
                case TRANSACTION_PROVIDER_UPDATE_SERVICE:
                    typeId = R.string.transaction_row_status_masternode_update_service;
                    break;
                default:
                    TransactionConfidence confidence = tx.getConfidence();
                    Wallet dashPayWallet = null;

                    if (wallet instanceof Wallet) {
                        dashPayWallet = (Wallet) wallet;
                    }

                    if (dashPayWallet != null && CreditFundingTransaction.isCreditFundingTransaction(tx)) {
                        AuthenticationGroupExtension authExtension =
                                (AuthenticationGroupExtension) dashPayWallet.getKeyChainExtension(AuthenticationGroupExtension.EXTENSION_ID);
                        CreditFundingTransaction cftx = authExtension.getCreditFundingTransaction(tx);

                        AuthenticationKeyChainGroup group = ((AuthenticationKeyChainGroup)authExtension.getKeyChainGroup());
                        switch (group.getKeyChainType(cftx.getCreditBurnPublicKeyId().getBytes())) {
                            case INVITATION_FUNDING:
                                typeId = R.string.dashpay_invite_fee;
                                break;
                           case BLOCKCHAIN_IDENTITY_FUNDING:
                               typeId = R.string.dashpay_upgrade_fee;
                               break;
                           case BLOCKCHAIN_IDENTITY_TOPUP:
                               typeId = R.string.dashpay_topup_fee;
                               break;
                           default:
                               typeId = R.string.transaction_row_status_sent;
                               break;
                        }
                    } else if(tx.getInputs().size() == tx.getOutputs().size() && tx.getValue(wallet).equals(Coin.ZERO)) {
                        typeId = R.string.transaction_row_status_coinjoin_mixing;
                    } else if (confidence.hasErrors())
                        typeId = R.string.transaction_row_status_error_sending;
                    else if (TransactionUtils.INSTANCE.isEntirelySelf(tx, wallet)) {
                        // internal transactions could be CoinJoin related transactions

                        if (isCoinJoinMixingFee(tx)) {
                            typeId = R.string.transaction_row_status_coinjoin_mixing_fee;
                        } else {
                            boolean makeCollateral = false;
                            if (tx.getOutputs().size() == 2) {
                                Coin nAmount0 = tx.getOutput(0).getValue();
                                Coin nAmount1 = tx.getOutput(1).getValue();
                                // <case1>, see CCoinJoinClientSession.makeCollateralAmounts
                                makeCollateral = (nAmount0.equals(CoinJoin.getMaxCollateralAmount()) && !CoinJoin.isDenominatedAmount(nAmount1) && nAmount1.isGreaterThanOrEqualTo(CoinJoin.getCollateralAmount())) ||
                                                 (nAmount1.equals(CoinJoin.getMaxCollateralAmount()) && !CoinJoin.isDenominatedAmount(nAmount0) && nAmount0.isGreaterThanOrEqualTo(CoinJoin.getCollateralAmount())) ||
                                        // <case2>, see CCoinJoinClientSession::MakeCollateralAmounts
                                        (nAmount0 == nAmount1 && CoinJoin.isCollateralAmount(nAmount0));
                            } else if (tx.getOutputs().size() == 1) {
                                // <case3>, see CCoinJoinClientSession::MakeCollateralAmounts
                                makeCollateral = CoinJoin.isCollateralAmount(tx.getOutput(0).getValue());
                            }
                            if (makeCollateral) {
                                typeId = R.string.transaction_row_status_coinjoin_make_collateral;
                            } else {
                                for (TransactionOutput output : tx.getOutputs()) {
                                    if (CoinJoin.isDenominatedAmount(output.getValue())) {
                                        typeId = R.string.transaction_row_status_coinjoin_create_denominations;
                                        break; // Done, it's definitely a tx creating mixing denoms, no need to look any further
                                    }
                                }
                            }
                            if (typeId == 0)
                                typeId = R.string.transaction_row_status_sent_internally;
                        }
                    } else if (confidence.getConfidenceType() == TransactionConfidence.ConfidenceType.BUILDING ||
                            (confidence.getConfidenceType() == TransactionConfidence.ConfidenceType.PENDING &&
                                (confidence.numBroadcastPeers() >= 1 || confidence.getIXType() == TransactionConfidence.IXType.IX_LOCKED ||
                                        (confidence.getPeerCount() == 1 && confidence.isSent()))))
                        typeId = R.string.transaction_row_status_sent;
                    else typeId = R.string.transaction_row_status_sending;
            }
        } else {
            // Not all coinbase transactions are v3 transactions with type 5 (coinbase)
            if (tx.getType() == Transaction.Type.TRANSACTION_COINBASE || tx.isCoinBase()) {
                //currently, we cannot tell if a coinbase transaction is a masternode mining reward
                typeId = R.string.transaction_row_status_mining_reward;
            }
            else typeId = R.string.transaction_row_status_received;
        }
        return typeId;
    }

    /**
     *
     * @param tx the transaction in question
     * @return the string id of the type of error or -1 no error name is known
     */
    @StringRes
    public int getErrorName(@NonNull Transaction tx) {
        TxError error = TxError.Companion.fromTransaction(tx);
        return getErrorName(error);
    }

    @StringRes
    public int getErrorName(@NonNull TxError error) {
        switch (error) {
            case DoubleSpend:
                return R.string.transaction_row_status_error_dead;
            case InConflict:
                return R.string.transaction_row_status_error_conflicting;
            case Nonstandard:
                return R.string.transaction_row_status_error_non_standard;
            case Dust:
                return R.string.transaction_row_status_error_dust;
            case InsufficientFee:
                return R.string.transaction_row_status_error_insufficient_fee;
            case Duplicate:
                return R.string.transaction_row_status_error_duplicate;
            case Invalid:
                return R.string.transaction_row_status_error_invalid;
            case Malformed:
                return R.string.transaction_row_status_error_malformed;
            case Obsolete:
                return R.string.transaction_row_status_error_obsolete;
            case Unknown:
            default:
                return R.string.transaction_row_status_error_other;
        }
    }

    /**
     * @return the secondary status or -1 if there is none
     */
    @StringRes
    public int getReceivedStatusString(Transaction tx, @NonNull Context context) {
        TransactionConfidence confidence = tx.getConfidence();
        int statusId = -1;
        if (confidence.getConfidenceType() == TransactionConfidence.ConfidenceType.BUILDING) {
            int confirmations = confidence.getDepthInBlocks();
            boolean isChainLocked = context.chainLockHandler.getBestChainLockBlockHeight() >= confidence.getDepthInBlocks();

            // process coinbase transactions (Mining Rewards) before other BUILDING transactions
            if (tx.isCoinBase()) {
                // coinbase transactions are locked if they have less than 100 confirmations
                if (confidence.getDepthInBlocks() < Constants.NETWORK_PARAMETERS.getSpendableCoinbaseDepth()) {
                    statusId = R.string.transaction_row_status_locked;
                }
            } else if (confirmations < 6 && !isChainLocked && confidence.getIXType() != TransactionConfidence.IXType.IX_LOCKED) {
                // confirmations < 6
                // not ChainLocked
                // not InstantSendLocked
                statusId = R.string.transaction_row_status_confirming;
            }
        } else if (confidence.getConfidenceType() == TransactionConfidence.ConfidenceType.PENDING) {
            switch (confidence.getIXType()) {
                case IX_LOCKED:
                    // no status string for InstantSendLocked transactions
                    break;
                case IX_REQUEST:
                    //received the InstantSendLock, but it has not been processed
                case IX_NONE:
                    //did not receive the InstantSendLock
                case IX_LOCK_FAILED:
                    //received the InstantSendLock, but verification failed
                    statusId = R.string.transaction_row_status_processing;
                    break;
            }
        }
        return statusId;
    }

    public int getDateTimeFormat() {
        return DateUtils.FORMAT_SHOW_TIME;
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
    public boolean isSending(Transaction tx, Wallet wallet) {
        Coin value = tx.getValue(wallet);
        TransactionConfidence confidence = tx.getConfidence();
        return !(value.isPositive() ||
                (confidence.getConfidenceType() == TransactionConfidence.ConfidenceType.BUILDING) ||
                (confidence.getConfidenceType() == TransactionConfidence.ConfidenceType.PENDING && (
                        (confidence.numBroadcastPeers() > 0 || confidence.getIXType() != TransactionConfidence.IXType.IX_LOCKED))));
    }
}
