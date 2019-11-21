/*
 * Copyright 2011-2015 the original author or authors.
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

package de.schildbach.wallet.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.RejectedTransactionException;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Transaction.Purpose;
import org.bitcoinj.core.TransactionConfidence;
import org.bitcoinj.core.TransactionConfidence.ConfidenceType;
import org.bitcoinj.utils.ExchangeRate;
import org.bitcoinj.utils.MonetaryFormat;
import org.bitcoinj.wallet.DefaultCoinSelector;
import org.bitcoinj.wallet.Wallet;
import org.dash.wallet.common.ui.CurrencyTextView;
import org.dash.wallet.common.ui.DialogBuilder;
import org.dash.wallet.common.ui.Formats;

import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.data.AddressBookProvider;
import de.schildbach.wallet.util.CircularProgressView;

import de.schildbach.wallet.util.WalletUtils;
import de.schildbach.wallet_test.R;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import androidx.core.content.res.ResourcesCompat;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;
import android.text.Html;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import static org.dash.wallet.common.Constants.PREFIX_ALMOST_EQUAL_TO;

/**
 * @author Andreas Schildbach
 */
public class TransactionsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private final Context context;
    private final LayoutInflater inflater;

    private final Wallet wallet;
    private final int maxConnectedPeers;
    @Nullable
    private final OnClickListener onClickListener;

    private final List<Transaction> transactions = new ArrayList<Transaction>();
    private MonetaryFormat format;

    private long selectedItemId = RecyclerView.NO_ID;

    private final int colorBackground, colorBackgroundSelected;
    private final int colorSignificant, colorLessSignificant, colorInsignificant;
    private final int colorValuePositve, colorValueNegative;
    private final int colorError;
    private final String textCoinBase;
    private final String textInternal;
    private final float textSizeNormal;
    private boolean showTransactionRowMenu;

    private static final String CONFIDENCE_SYMBOL_IN_CONFLICT = "\u26A0"; // warning sign
    private static final String CONFIDENCE_SYMBOL_DEAD = "\u271D"; // latin cross
    private static final String CONFIDENCE_SYMBOL_UNKNOWN = "?";

    private static final int VIEW_TYPE_TRANSACTION = 0;

    private Map<Sha256Hash, TransactionCacheEntry> transactionCache = new HashMap<Sha256Hash, TransactionCacheEntry>();

    private static class TransactionCacheEntry {
        private final Coin value;
        private final boolean sent;
        private final boolean self;
        private final boolean showFee;
        @Nullable
        private final Address address;
        @Nullable
        private final String addressLabel;
        private final Transaction.Type type;

        private TransactionCacheEntry(final Coin value, final boolean sent, final boolean self, final boolean showFee, final @Nullable Address address,
                                      final @Nullable String addressLabel, final Transaction.Type type) {
            this.value = value;
            this.sent = sent;
            this.self = self;
            this.showFee = showFee;
            this.address = address;
            this.addressLabel = addressLabel;
            this.type = type;
        }
    }

    public TransactionsAdapter(final Context context, final Wallet wallet,
                               final int maxConnectedPeers, final @Nullable OnClickListener onClickListener) {
        this.context = context;
        inflater = LayoutInflater.from(context);

        this.wallet = wallet;
        this.maxConnectedPeers = maxConnectedPeers;
        this.onClickListener = onClickListener;

        final Resources res = context.getResources();
        colorBackground = res.getColor(R.color.bg_bright);
        colorBackgroundSelected = res.getColor(R.color.bg_panel);
        colorSignificant = res.getColor(R.color.fg_significant);
        colorLessSignificant = res.getColor(R.color.fg_less_significant);
        colorInsignificant = res.getColor(R.color.fg_insignificant);
        colorValuePositve = res.getColor(R.color.colorPrimary);
        colorValueNegative = res.getColor(android.R.color.black);
        colorError = res.getColor(R.color.fg_error);
        textCoinBase = context.getString(R.string.wallet_transactions_fragment_coinbase);
        textInternal = context.getString(R.string.symbol_internal) + " "
                + context.getString(R.string.wallet_transactions_fragment_internal);
        textSizeNormal = res.getDimension(R.dimen.font_size_normal);

        setHasStableIds(true);
    }

    public void setFormat(final MonetaryFormat format) {
        this.format = format.noCode();

        notifyDataSetChanged();
    }

    public void clear() {
        transactions.clear();

        notifyDataSetChanged();
    }

    public void replace(final Transaction tx) {
        transactions.clear();
        transactions.add(tx);

        notifyDataSetChanged();
    }

    public void replace(final Collection<Transaction> transactions) {
        this.transactions.clear();
        this.transactions.addAll(transactions);

        notifyDataSetChanged();
    }

    public void setSelectedItemId(final long itemId) {
        selectedItemId = itemId;

        notifyDataSetChanged();
    }

    public void clearCacheAndNotifyDataSetChanged() {
        transactionCache.clear();

        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        int count = transactions.size();

        return count;
    }

    public int getTransactionsCount() {
        return transactions.size();
    }

    @Override
    public long getItemId(int position) {
        if (position == RecyclerView.NO_POSITION)
            return RecyclerView.NO_ID;

        return WalletUtils.longHash(transactions.get(position).getHash());
    }

    @Override
    public int getItemViewType(final int position) {
        return VIEW_TYPE_TRANSACTION;
    }

    public RecyclerView.ViewHolder createTransactionViewHolder(final ViewGroup parent) {
        return createViewHolder(parent, VIEW_TYPE_TRANSACTION);
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(final ViewGroup parent, final int viewType) {
        if (viewType == VIEW_TYPE_TRANSACTION) {
            return new TransactionViewHolder(inflater.inflate(R.layout.transaction_row_redesign, parent, false));
        } else {
            throw new IllegalStateException("unknown type: " + viewType);
        }
    }

    @Override
    public void onBindViewHolder(final RecyclerView.ViewHolder holder, final int position) {
        if (holder instanceof TransactionViewHolder) {
            final TransactionViewHolder transactionHolder = (TransactionViewHolder) holder;

            final long itemId = getItemId(position);
            transactionHolder.itemView.setActivated(itemId == selectedItemId);
            //transactionHolder.itemView.setBackgroundColor(itemId == selectedItemId ? R.color.dash_gray : R.color.dash_white);

            final Transaction tx = transactions.get(position);
            transactionHolder.bind(tx);

            transactionHolder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(final View v) {
                    Transaction tx = transactions.get(transactionHolder.getAdapterPosition());
                    if (onClickListener != null) {
                        onClickListener.onTransactionRowClicked(tx);
                    }
                    //setSelectedItemId(getItemId(transactionHolder.getAdapterPosition()));
                }
            });

            if (onClickListener != null) {
           //     transactionHolder.menuView.setOnClickListener(new View.OnClickListener() {
           //         @Override
           //         public void onClick(final View v) {
           //             onClickListener.onTransactionMenuClick(v, tx);
           //         }
           //     });
            }

            //transactionHolder.menuView.setVisibility(showTransactionRowMenu ? View.VISIBLE : View.INVISIBLE);
        }
    }

    public void setShowTransactionRowMenu(boolean showTransactionRowMenu) {
        this.showTransactionRowMenu = showTransactionRowMenu;
    }

    public interface OnClickListener {
        void onTransactionMenuClick(View view, Transaction tx);
        void onTransactionRowClicked(Transaction tx);
    }

    private class TransactionViewHolder extends RecyclerView.ViewHolder {
        //private final CircularProgressView confidenceCircularView;
        //private final TextView confidenceTextualView;
        private final TextView primaryStatusView;
        private final TextView secondaryStatusView;
        private final TextView timeView;
        //private final TextView addressView;
        private final ImageView dashSymbolView;
        private final CurrencyTextView valueView;
        private final TextView signalView;
        //private final TextView valueInternalView;
        //private final View extendedFeeView;
        //private final CurrencyTextView feeView;
        private final CurrencyTextView fiatView;
        private final TextView rateNotAvailableView;
        //private final View extendMessageView;
        //private final TextView messageView;
        //private final ImageButton menuView;
        //private final ImageView ixInfoButtonView;

        private TransactionViewHolder(final View itemView) {
            super(itemView);
            //confidenceCircularView = (CircularProgressView) itemView
            //        .findViewById(R.id.transaction_row_confidence_circular);
            //confidenceTextualView = (TextView) itemView.findViewById(R.id.transaction_row_confidence_textual);
            primaryStatusView = (TextView) itemView.findViewById(R.id.transaction_row_primary_status);
            secondaryStatusView = (TextView) itemView.findViewById(R.id.transaction_row_secondary_status);

            timeView = (TextView) itemView.findViewById(R.id.transaction_row_time);
            //addressView = (TextView) itemView.findViewById(R.id.transaction_row_address);
            dashSymbolView = (ImageView) itemView.findViewById(R.id.dash_amount_symbol);
            valueView = (CurrencyTextView) itemView.findViewById(R.id.transaction_row_value);
            signalView = (TextView) itemView.findViewById(R.id.transaction_amount_signal);
            //valueInternalView = (TextView) itemView.findViewById(R.id.transaction_amount_signal);

            //extendedFeeView = itemView.findViewById(R.id.transaction_row_extend_fee);
            //feeView = (CurrencyTextView) itemView.findViewById(R.id.transaction_row_fee);
            fiatView = (CurrencyTextView) itemView.findViewById(R.id.transaction_row_fiat);
            fiatView.setApplyMarkup(false);
            rateNotAvailableView = (TextView) itemView.findViewById(R.id.transaction_row_rate_not_available);
            //extendMessageView = itemView.findViewById(R.id.transaction_row_extend_message);
            //messageView = (TextView) itemView.findViewById(R.id.transaction_row_message);
            //menuView = (ImageButton) itemView.findViewById(R.id.transaction_row_menu);
            //Dash
            //ixInfoButtonView = itemView.findViewById(R.id.transaction_row_info_button);
        }

        private void bind(final Transaction tx) {
            if (itemView instanceof CardView)
                ((CardView) itemView)
                        .setCardBackgroundColor(itemView.isActivated() ? colorBackgroundSelected : colorBackground);

            final TransactionConfidence confidence = tx.getConfidence();
            final ConfidenceType confidenceType = confidence.getConfidenceType();
            final boolean isOwn = confidence.getSource().equals(TransactionConfidence.Source.SELF);
            final boolean isCoinBase = tx.isCoinBase();
            final Transaction.Purpose purpose = tx.getPurpose();
            final Coin fee = tx.getFee();
            final String[] memo = Formats.sanitizeMemo(tx.getMemo());

            final TransactionConfidence.IXType ixStatus = confidence.getIXType();
            final StoredBlock chainLockBlock = org.bitcoinj.core.Context.get().chainLockHandler.getBestChainLockBlock();
            final int chainLockHeight = chainLockBlock != null && confidence.getConfidenceType() == ConfidenceType.BUILDING ? chainLockBlock.getHeight() : -1;
            final boolean isChainLocked = chainLockHeight != -1 ? confidence.getAppearedAtChainHeight() <= chainLockHeight : false;
            final boolean sentToSinglePeer = confidence.getPeerCount() == 1;
            final boolean sentToSinglePeerSuccessful = sentToSinglePeer ? confidence.isSent() : false;

            TransactionCacheEntry txCache = transactionCache.get(tx.getHash());
            if (txCache == null) {
                final Coin value = tx.getValue(wallet);
                final boolean sent = value.signum() < 0;
                final boolean self = WalletUtils.isEntirelySelf(tx, wallet);
                final boolean showFee = sent && fee != null && !fee.isZero();
                final Address address;
                if (sent)
                    address = WalletUtils.getToAddressOfSent(tx, wallet);
                else
                    address = WalletUtils.getWalletAddressOfReceived(tx, wallet);
                final String addressLabel = address != null
                        ? AddressBookProvider.resolveLabel(context, address.toBase58()) : null;

                final Transaction.Type txType = tx.getType();

                txCache = new TransactionCacheEntry(value, sent, self, showFee, address, addressLabel, txType);
                transactionCache.put(tx.getHash(), txCache);
            }

            final int textColor, lessSignificantColor, valueColor;
            if (confidenceType == ConfidenceType.DEAD) {
                textColor = colorError;
                lessSignificantColor = colorError;
                valueColor = colorError;
            } else if (DefaultCoinSelector.get().isSelectable(tx)) {
                textColor = colorSignificant;
                lessSignificantColor = colorLessSignificant;
                valueColor = txCache.sent ? colorValueNegative : colorValuePositve;
            } else {
                textColor = colorInsignificant;
                lessSignificantColor = colorInsignificant;
                valueColor = colorInsignificant;
            }



            // confidence
            if (confidenceType == ConfidenceType.PENDING) {
                //confidenceCircularView.setVisibility(View.VISIBLE);
                //confidenceTextualView.setVisibility(View.GONE);

                //confidenceCircularView.setProgress(1);
                //confidenceCircularView.setMaxProgress(1);
                if (ixStatus == TransactionConfidence.IXType.IX_LOCKED) {
                //    confidenceCircularView.setProgress(5);
                //    confidenceCircularView.setMaxProgress(Constants.MAX_NUM_CONFIRMATIONS);
                //    confidenceCircularView.setColors(valueColor, Color.TRANSPARENT);
                } else {
                    //secondaryStatusView.setText(R.string.transaction_row_status_processing);
                //    confidenceCircularView.setSize(confidence.numBroadcastPeers());
                //    confidenceCircularView.setMaxSize(maxConnectedPeers / 2); // magic value
                //    confidenceCircularView.setColors(colorInsignificant, Color.TRANSPARENT);
                }
            } else if (confidenceType == ConfidenceType.IN_CONFLICT) {
                //confidenceCircularView.setVisibility(View.GONE);
                //confidenceTextualView.setVisibility(View.VISIBLE);

                //confidenceTextualView.setText(CONFIDENCE_SYMBOL_IN_CONFLICT);
                //confidenceTextualView.setTextColor(colorError);
                //confidenceTextualView.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizeNormal * 0.85f);
                primaryStatusView.setTextColor(colorError);
                secondaryStatusView.setTextColor(colorError);
            } else if (confidenceType == ConfidenceType.BUILDING) {

                //confidenceCircularView.setVisibility(View.VISIBLE);
                //confidenceTextualView.setVisibility(View.GONE);

                //confidenceCircularView.setProgress(isLocked ? confidence.getDepthInBlocks() + 5 : confidence.getDepthInBlocks());
                //confidenceCircularView.setMaxProgress(isCoinBase ? Constants.NETWORK_PARAMETERS.getSpendableCoinbaseDepth()
                //        : Constants.MAX_NUM_CONFIRMATIONS);
                //confidenceCircularView.setSize(1);
                //confidenceCircularView.setMaxSize(1);
                //confidenceCircularView.setColors(valueColor, Color.TRANSPARENT);
            } else if (confidenceType == ConfidenceType.DEAD) {
                //confidenceCircularView.setVisibility(View.GONE);
                //confidenceTextualView.setVisibility(View.VISIBLE);

                //confidenceTextualView.setText(CONFIDENCE_SYMBOL_DEAD);
                //confidenceTextualView.setTextColor(colorError);
                //confidenceTextualView.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizeNormal);
                primaryStatusView.setTextColor(colorError);
                secondaryStatusView.setTextColor(colorError);
            } else {
                //confidenceCircularView.setVisibility(View.GONE);
                //confidenceTextualView.setVisibility(View.VISIBLE);

                //confidenceTextualView.setText(CONFIDENCE_SYMBOL_UNKNOWN);
                //confidenceTextualView.setTextColor(colorInsignificant);
                //confidenceTextualView.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizeNormal);
                secondaryStatusView.setText(R.string.transaction_row_status_unknown);
            }

            // time
            final Date time = tx.getUpdateTime();
            //if (!itemView.isActivated()) {
            //    timeView.setText(DateUtils.getRelativeTimeSpanString(context, time.getTime()));
            //} else {
                String onTimeText = context.getString(R.string.transaction_row_time_text);

                timeView.setText(String.format(onTimeText, DateUtils.formatDateTime(context, time.getTime(),
                        DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR), DateUtils.formatDateTime(context, time.getTime(),
                        DateUtils.FORMAT_SHOW_TIME)));
            //}

            Typeface defaultTypeface = ResourcesCompat.getFont(context, R.font.montserrat_medium);
            Typeface boldTypeface = ResourcesCompat.getFont(context, R.font.montserrat_semibold);

            //primaryStatusView.setTypeface(boldTypeface);
            //secondaryStatusView.setTypeface(boldTypeface);

            // primary status - Sent:  Sent, Masternode Special Tx's, Internal
            //                  Received:  Received, Mining Rewards, Masternode Rewards
            int idPrimaryStatus;
            if(txCache.sent) {
                idPrimaryStatus = R.string.transaction_row_status_sent;
                if(txCache.type == Transaction.Type.TRANSACTION_PROVIDER_REGISTER)
                    idPrimaryStatus = R.string.transaction_row_status_masternode_registration;
                else if (txCache.type == Transaction.Type.TRANSACTION_PROVIDER_UPDATE_REGISTRAR)
                    idPrimaryStatus = R.string.transaction_row_status_masternode_update_registrar;
                else if (txCache.type == Transaction.Type.TRANSACTION_PROVIDER_UPDATE_REVOKE)
                    idPrimaryStatus = R.string.transaction_row_status_masternode_revoke;
                else if (txCache.type == Transaction.Type.TRANSACTION_PROVIDER_UPDATE_SERVICE)
                    idPrimaryStatus = R.string.transaction_row_status_masternode_update_service;
                else if(txCache.self)
                    idPrimaryStatus = R.string.transaction_row_status_sent_interally;
            } else {
                // Not all coinbase transactions are v3 transactions with type 5 (coinbase)
                if (txCache.type == Transaction.Type.TRANSACTION_COINBASE || isCoinBase) {
                    //currently, we cannot tell if a coinbase transaction is a masternode mining reward
                    idPrimaryStatus = R.string.transaction_row_status_mining_reward;
                }
                else idPrimaryStatus = R.string.transaction_row_status_received;
            }
            primaryStatusView.setText(idPrimaryStatus);
            secondaryStatusView.setText(null);

            // address
            if (isCoinBase) {
                if(confidence.getDepthInBlocks() < Constants.NETWORK_PARAMETERS.getSpendableCoinbaseDepth())
                    secondaryStatusView.setText(R.string.transaction_row_status_locked);
                //addressView.setTypeface(boldTypeface);
                //addressView.setText(textCoinBase);
            } else if (purpose == Purpose.KEY_ROTATION || txCache.self) {
                //addressView.setTypeface(boldTypeface);
                //addressView.setText(textInternal);
            } else if (txCache.addressLabel != null) {
                //addressView.setTypeface(boldTypeface);
                //addressView.setText(txCache.addressLabel);
            } else if (memo != null && memo.length >= 2) {
                //addressView.setTypeface(boldTypeface);
                //addressView.setText(memo[1]);
            } else if (txCache.address != null) {
                //addressView.setTypeface(defaultTypeface);
                //if (!itemView.isActivated()) {
                //    addressView.setText(WalletUtils.buildShortAddress(txCache.address.toBase58()));
                //} else {
                //    addressView.setText(WalletUtils.formatAddress(txCache.address, Constants.ADDRESS_FORMAT_GROUP_SIZE,
                //            Constants.ADDRESS_ROW_FORMAT_LINE_SIZE));
                //}
            } else {
                //addressView.setTextColor(lessSignificantColor);
                //addressView.setTypeface(Typeface.DEFAULT);
                //addressView.setText("?");
            }
            //addressView.setSingleLine(!itemView.isActivated());

            // fee
            if (txCache.showFee) {
            //    extendedFeeView.setVisibility(itemView.isActivated()
            //            || (confidenceType == ConfidenceType.PENDING && purpose != Purpose.RAISE_FEE) ? View.VISIBLE
            //            : View.GONE);
            //    feeView.setAlwaysSigned(true);
            //    feeView.setFormat(format);
            //    feeView.setAmount(fee.negate());
            } else {
            //    extendedFeeView.setVisibility(View.GONE);
            }

            // value
            //valueView.setAlwaysSigned(true);
            valueView.setFormat(format);
            final Coin value;
            valueView.setTextColor(valueColor);
            signalView.setTextColor(valueColor);
            dashSymbolView.setColorFilter(valueColor);
            value = txCache.showFee ? txCache.value.add(fee) : txCache.value;


            valueView.setVisibility(/*!value.isZero() ? */View.VISIBLE /*: View.GONE*/);
            signalView.setVisibility(!value.isZero() ? View.VISIBLE : View.GONE);
            dashSymbolView.setVisibility(/*!value.isZero() ?*/ View.VISIBLE /*: View.GONE*/);
            //valueInternalView.setVisibility(/*value.isZero() ? View.VISIBLE : */View.GONE);
            if(value.isPositive()) {
                signalView.setText(String.format("%c", org.dash.wallet.common.Constants.CURRENCY_PLUS_SIGN)/*"+"*/);
                valueView.setAmount(value);
            } else if(value.isNegative()) {
                signalView.setText(String.format("%c", org.dash.wallet.common.Constants.CURRENCY_MINUS_SIGN) /*"-"*/);
                valueView.setAmount(value.negate());
            } else {
                //internal
                valueView.setAmount(Coin.ZERO);
                //valueInternalView.setText(textInternal);
            }

            // fiat value
            if(!value.isZero()) {
                final ExchangeRate exchangeRate = tx.getExchangeRate();
                String exchangeCurrencyCode = WalletApplication.getInstance().getConfiguration()
                        .getExchangeCurrencyCode();
                if(exchangeRate != null) {
                    fiatView.setFiatAmount(txCache.value, exchangeRate, Constants.LOCAL_FORMAT,
                            exchangeCurrencyCode);
                    fiatView.setVisibility(View.VISIBLE);
                    rateNotAvailableView.setVisibility(View.GONE);
                } else {
                    //    extendMessageView.setVisibility(View.VISIBLE);
                    //    messageView.setSingleLine(false);
                    //    messageView.setText(R.string.exchange_rate_missing);
                    fiatView.setVisibility(View.GONE);
                    rateNotAvailableView.setVisibility(View.VISIBLE);
                }
            } else {
                fiatView.setVisibility(View.GONE);
                rateNotAvailableView.setVisibility(View.GONE);
            }

            // message
            //extendMessageView.setVisibility(View.GONE);
            //messageView.setSingleLine(false);



            if (purpose == Purpose.KEY_ROTATION) {
            //    extendMessageView.setVisibility(View.VISIBLE);
             //   messageView.setText(
            //            Html.fromHtml(context.getString(R.string.transaction_row_message_purpose_key_rotation)));
            //    messageView.setTextColor(colorSignificant);
            } else if(isOwn && confidenceType == ConfidenceType.UNKNOWN) {
                //In this case, check for rejections from the network
                RejectedTransactionException exception = confidence.getRejectedTransactionException();
                if(exception != null) {
                    primaryStatusView.setText(R.string.transaction_row_status_error_sending);
                    int idSecondaryStatus;
                    switch(exception.getRejectMessage().getReasonCode()) {
                        case NONSTANDARD:
                            idSecondaryStatus = R.string.transaction_row_status_error_non_standard;
                        case DUST:
                            idSecondaryStatus = R.string.transaction_row_status_error_dust;
                        case INSUFFICIENTFEE:
                            idSecondaryStatus = R.string.transaction_row_status_error_insufficient_fee;
                        case DUPLICATE:
                        case INVALID:
                        case MALFORMED:
                        case OBSOLETE:
                        case CHECKPOINT:
                        case OTHER:
                        default:
                            idSecondaryStatus = R.string.transaction_row_status_error_other;
                            break;
                    }
                    secondaryStatusView.setText(idSecondaryStatus);
                }
            } else if (isOwn && confidenceType == ConfidenceType.PENDING && sentToSinglePeer && ixStatus != TransactionConfidence.IXType.IX_LOCKED && confidence.numBroadcastPeers() == 0) {
                secondaryStatusView.setText(R.string.transaction_row_status_sending);
                //    extendMessageView.setVisibility(View.VISIBLE);
            //    if(sentToSinglePeerSuccessful)
            //        messageView.setText(R.string.transaction_row_message_sent_to_single_peer);
            //    else messageView.setText(R.string.transaction_row_message_own_unbroadcasted);
            //    messageView.setTextColor(colorInsignificant);
            } else if (isOwn && confidenceType == ConfidenceType.PENDING && confidence.numBroadcastPeers() == 0) {
                primaryStatusView.setText(R.string.transaction_row_status_sending);
                //    extendMessageView.setVisibility(View.VISIBLE);
            //    messageView.setText(R.string.transaction_row_message_own_unbroadcasted);
            //    messageView.setTextColor(colorInsignificant);
            //    if (txCache.isIX) {
            //        messageView.setText(R.string.transaction_row_message_own_instantx_lock_request_notsent);
            //    }
            } else if (isOwn && confidenceType == ConfidenceType.PENDING &&
                    (confidence.numBroadcastPeers() > 0 || ixStatus == TransactionConfidence.IXType.IX_LOCKED)) {
                primaryStatusView.setText(R.string.transaction_row_status_sent);

            } else if (!isOwn && confidenceType == ConfidenceType.PENDING && confidence.numBroadcastPeers() == 0) {
                secondaryStatusView.setText(R.string.transaction_row_status_processing);
                //    extendMessageView.setVisibility(View.VISIBLE);
            //    messageView.setText(R.string.transaction_row_message_received_direct);
            //    messageView.setTextColor(colorInsignificant);
            } else if (!txCache.sent && txCache.value.compareTo(Transaction.MIN_NONDUST_OUTPUT) < 0) {
            //    extendMessageView.setVisibility(View.VISIBLE);
            //    messageView.setText(R.string.transaction_row_message_received_dust);
            //    messageView.setTextColor(colorInsignificant);
            } else if (!txCache.sent && confidenceType == ConfidenceType.PENDING
                    && (tx.getUpdateTime() == null || wallet.getLastBlockSeenTimeSecs() * 1000/
                           - tx.getUpdateTime().getTime() > Constants.DELAYED_TRANSACTION_THRESHOLD_MS)) {

                switch(confidence.getIXType()) {
                    case IX_LOCKED:
                        secondaryStatusView.setText(null);
                        break;
                    case IX_REQUEST:
                    case IX_NONE:
                        secondaryStatusView.setText(R.string.transaction_row_status_processing);
                        break;
                    case IX_LOCK_FAILED:
                        secondaryStatusView.setText(null);
                        break;
                }

                //    extendMessageView.setVisibility(View.VISIBLE);
            //    messageView.setText(R.string.transaction_row_message_received_unconfirmed_delayed);
            //    messageView.setTextColor(colorInsignificant);
            } else if (!txCache.sent && confidenceType == ConfidenceType.PENDING) {
                switch(confidence.getIXType()) {
                    case IX_LOCKED:
                        secondaryStatusView.setText(null);
                        break;
                    case IX_REQUEST:
                    case IX_NONE:
                        secondaryStatusView.setText(R.string.transaction_row_status_processing);
                        break;
                    case IX_LOCK_FAILED:
                        secondaryStatusView.setText(null);
                        break;
                }
                //    extendMessageView.setVisibility(View.VISIBLE);
            //    messageView.setText(R.string.transaction_row_message_received_unconfirmed_unlocked);
            //    messageView.setTextColor(colorInsignificant);
            } else if (!txCache.sent && confidenceType == ConfidenceType.BUILDING) {
                int confirmations = confidence.getDepthInBlocks();
                if(tx.isCoinBase()) {
                    // for a coinbase transaction, InstantSend does not apply, Chainlocks don't affect Locked state
                    if(Constants.NETWORK_PARAMETERS.getSpendableCoinbaseDepth() > confidence.getDepthInBlocks())
                        secondaryStatusView.setText(R.string.transaction_row_status_locked);
                } else if(confirmations < 6 && !isChainLocked && ixStatus != TransactionConfidence.IXType.IX_LOCKED)
                    secondaryStatusView.setText(R.string.transaction_row_status_confirming);
                //    extendMessageView.setVisibility(View.VISIBLE);
                //    messageView.setText(R.string.transaction_row_message_received_unconfirmed_unlocked);
                //    messageView.setTextColor(colorInsignificant);
            } else if (!txCache.sent && confidenceType == ConfidenceType.IN_CONFLICT) {
                secondaryStatusView.setText(R.string.transaction_row_status_error_conflicting);
                //    extendMessageView.setVisibility(View.VISIBLE);
            //    messageView.setText(R.string.transaction_row_message_received_in_conflict);
            //    messageView.setTextColor(colorInsignificant);
            } else if (!txCache.sent && confidenceType == ConfidenceType.DEAD) {
                secondaryStatusView.setText(R.string.transaction_row_status_error_dead);
                //    extendMessageView.setVisibility(View.VISIBLE);
            //    messageView.setText(R.string.transaction_row_message_received_dead);
            //    messageView.setTextColor(colorError);
            } else if (!txCache.sent && WalletUtils.isPayToManyTransaction(tx)) {
            //    extendMessageView.setVisibility(View.VISIBLE);
            //    messageView.setText(R.string.transaction_row_message_received_pay_to_many);
            //    messageView.setTextColor(colorInsignificant);
            } else if (memo != null) {
            //    extendMessageView.setVisibility(View.VISIBLE);
            //    messageView.setText(memo[0]);
            //    messageView.setTextColor(colorInsignificant);
            //    messageView.setSingleLine(!itemView.isActivated());
            }

            //ixInfoButtonView.setVisibility(View.GONE);
            /*boolean isSimple = tx.isSimple();
            TransactionConfidence.IXType ixType = confidence.getIXType();
            boolean ixLockFailed = ixType == TransactionConfidence.IXType.IX_LOCK_FAILED;
            if (isOwn && isSimple && ixLockFailed) {
                ixInfoButtonView.setVisibility(isLocked ? View.GONE : View.VISIBLE);
                ixInfoButtonView.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        new DialogBuilder(context)
                                .setMessage(R.string.regular_transaction_info_message)
                                .setPositiveButton(R.string.button_ok, null)
                                .show();
                    }
                });
            }*/
        }
    }
}
