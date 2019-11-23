package de.schildbach.wallet.util;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.RejectedTransactionException;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionConfidence;
import org.bitcoinj.wallet.Wallet;

import de.schildbach.wallet.Constants;
import de.schildbach.wallet_test.R;

public class TransactionUtil {

    /**
     *
     * @param tx the transaction in question
     * @param wallet the wallet that contains the transaction
     * @return resource id of the string that holds the name of the transaction type
     */
    public static int getTransactionTypeName(Transaction tx, Wallet wallet) {
        int typeId;
        if(tx.getValue(wallet).signum() < 0) {
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
                    if (WalletUtils.isEntirelySelf(tx, wallet))
                        typeId = R.string.transaction_row_status_sent_interally;
                    else
                        typeId = R.string.transaction_row_status_sent;
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
    public static int getErrorName(Transaction tx) {
        TransactionConfidence confidence = tx.getConfidence();
        int errorNameId = -1;
        if(confidence != null) {
            if(confidence.getConfidenceType() == TransactionConfidence.ConfidenceType.DEAD) {
                errorNameId = R.string.transaction_row_status_error_dead;
            } else if(confidence.getConfidenceType() == TransactionConfidence.ConfidenceType.IN_CONFLICT) {
                errorNameId = R.string.transaction_row_status_error_conflicting;
            } else if(confidence.getConfidenceType() != TransactionConfidence.ConfidenceType.BUILDING) {
                //
                // Handle errors from the Dash Network
                //
                RejectedTransactionException exception = confidence.getRejectedTransactionException();
                if (exception != null) {
                    switch (exception.getRejectMessage().getReasonCode()) {
                        case NONSTANDARD:
                            errorNameId = R.string.transaction_row_status_error_non_standard;
                            break;
                        case DUST:
                            errorNameId = R.string.transaction_row_status_error_dust;
                            break;
                        case INSUFFICIENTFEE:
                            errorNameId = R.string.transaction_row_status_error_insufficient_fee;
                            break;
                        case DUPLICATE:
                            errorNameId = R.string.transaction_row_status_error_duplicate;
                            break;
                        case INVALID:
                            errorNameId = R.string.transaction_row_status_error_invalid;
                            break;
                        case MALFORMED:
                            errorNameId = R.string.transaction_row_status_error_malformed;
                            break;
                        case OBSOLETE:
                            errorNameId = R.string.transaction_row_status_error_obsolete;
                            break;
                        case CHECKPOINT: //checkpoint rejections do not apply to transactions
                        case OTHER:
                        default:
                            errorNameId = R.string.transaction_row_status_error_other;
                            break;
                    }
                    return errorNameId;
                }
            }
        }
        return errorNameId;
    }

    /**
     *
     * @param tx
     * @param wallet
     * @return the secondary status or -1 if there is none
     */
    public static int getSecondaryStatusString(Transaction tx, Wallet wallet) {
        TransactionConfidence confidence = tx.getConfidence();
        int statusId = -1;
        if (confidence.getConfidenceType() == TransactionConfidence.ConfidenceType.BUILDING) {
            int confirmations = confidence.getDepthInBlocks();
            boolean isChainLocked = wallet.getContext().chainLockHandler.getBestChainLockBlockHeight() >= confidence.getDepthInBlocks();

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
                    //received the InstantSendLock, but it wasn't verified
                    statusId = R.string.transaction_row_status_processing;
                    break;
            }
        }
        return statusId;
    }

}
