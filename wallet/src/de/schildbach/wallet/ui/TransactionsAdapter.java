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
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionConfidence;
import org.bitcoinj.core.TransactionConfidence.ConfidenceType;
import org.bitcoinj.utils.ExchangeRate;
import org.bitcoinj.utils.MonetaryFormat;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.ZeroConfCoinSelector;
import org.dash.wallet.common.transactions.TransactionWrapper;
import org.dash.wallet.common.ui.CurrencyTextView;
import org.dash.wallet.common.ui.Formats;
import org.dash.wallet.common.util.GenericUtils;

import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.data.AddressBookProvider;

import de.schildbach.wallet.util.FiatExtensionsKt;
import de.schildbach.wallet.util.TransactionUtil;
import de.schildbach.wallet.util.WalletUtils;
import de.schildbach.wallet_test.R;

import android.content.Context;
import android.content.res.Resources;

import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;

import android.text.format.DateUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * @author Andreas Schildbach
 */
public class TransactionsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private final LayoutInflater inflater;

    private final Wallet wallet;
    @Nullable
    private final OnClickListener onClickListener;

    private final List<Transaction> transactions = new ArrayList<>();
    private MonetaryFormat format;

    private final int colorBackground, colorBackgroundSelected;
    private final int colorPrimaryStatus, colorSecondaryStatus;
    private final int colorValuePositve, colorValueNegative;
    private final int colorError;

    private static final int VIEW_TYPE_TRANSACTION = 0;

    private final Map<Sha256Hash, TransactionCacheEntry> transactionCache = new HashMap<>();

    private static class TransactionCacheEntry {
        private final Coin value;
        private final boolean sent;
        private final boolean showFee;

        private TransactionCacheEntry(final Coin value, final boolean sent, final boolean showFee) {
            this.value = value;
            this.sent = sent;
            this.showFee = showFee;
        }
    }

    public TransactionsAdapter(final Context context, final Wallet wallet,
                               final @Nullable OnClickListener onClickListener) {
        inflater = LayoutInflater.from(context);

        this.wallet = wallet;
        this.onClickListener = onClickListener;

        final Resources res = context.getResources();
        colorBackground = res.getColor(R.color.bg_bright);
        colorBackgroundSelected = res.getColor(R.color.bg_panel);
        colorPrimaryStatus = res.getColor(R.color.primary_status);
        colorSecondaryStatus = res.getColor(R.color.secondary_status);
        colorValuePositve = res.getColor(R.color.colorPrimary);
        colorValueNegative = res.getColor(android.R.color.black);
        colorError = res.getColor(R.color.fg_error);

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

    public void replace(final Collection<TransactionWrapper> wrappedTransactions) {
        Log.i("CROWDNODE", "replacing");
        ArrayList<Transaction> unwrappedTransactions = new ArrayList<>();

        for (TransactionWrapper wrapper: wrappedTransactions) {
            unwrappedTransactions.addAll(wrapper.getTransactions());
        }

        this.transactions.clear();
        this.transactions.addAll(unwrappedTransactions);

        notifyDataSetChanged();
    }

    public void clearCacheAndNotifyDataSetChanged() {
        transactionCache.clear();

        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
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

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(final ViewGroup parent, final int viewType) {
        if (viewType == VIEW_TYPE_TRANSACTION) {
            return new TransactionViewHolder(inflater.inflate(R.layout.transaction_row, parent, false));
        } else {
            throw new IllegalStateException("unknown type: " + viewType);
        }
    }

    @Override
    public void onBindViewHolder(final RecyclerView.ViewHolder holder, final int position) {
        if (holder instanceof TransactionViewHolder) {
            final TransactionViewHolder transactionHolder = (TransactionViewHolder) holder;

            final long itemId = getItemId(position);
            long selectedItemId = RecyclerView.NO_ID;
            transactionHolder.itemView.setActivated(itemId == selectedItemId);

            final Transaction tx = transactions.get(position);
            transactionHolder.bind(tx);

            transactionHolder.itemView.setOnClickListener(v -> {
                Transaction tx1 = transactions.get(transactionHolder.getAdapterPosition());
                if (onClickListener != null) {
                    onClickListener.onTransactionRowClicked(tx1);
                }
            });
        }
    }

    public interface OnClickListener {
        void onTransactionRowClicked(Transaction tx);
    }

    private class TransactionViewHolder extends RecyclerView.ViewHolder {
        private final TextView primaryStatusView;
        private final TextView secondaryStatusView;
        private final TextView timeView;
        private final ImageView dashSymbolView;
        private final CurrencyTextView valueView;
        private final TextView signalView;
        private final CurrencyTextView fiatView;
        private final TextView rateNotAvailableView;

        private TransactionViewHolder(final View itemView) {
            super(itemView);
            primaryStatusView = itemView.findViewById(R.id.transaction_row_primary_status);
            secondaryStatusView = itemView.findViewById(R.id.transaction_row_secondary_status);

            timeView = itemView.findViewById(R.id.transaction_row_time);
            dashSymbolView = itemView.findViewById(R.id.dash_amount_symbol);
            valueView = itemView.findViewById(R.id.transaction_row_value);
            signalView = itemView.findViewById(R.id.transaction_amount_signal);

            fiatView = itemView.findViewById(R.id.transaction_row_fiat);
            fiatView.setApplyMarkup(false);
            rateNotAvailableView = itemView.findViewById(R.id.transaction_row_rate_not_available);
        }

        private void bind(final Transaction tx) {
            if (itemView instanceof CardView)
                ((CardView) itemView)
                        .setCardBackgroundColor(itemView.isActivated() ? colorBackgroundSelected : colorBackground);

            final TransactionConfidence confidence = tx.getConfidence();
            final Coin fee = tx.getFee();

            TransactionCacheEntry txCache = transactionCache.get(tx.getTxId());
            if (txCache == null) {
                final Coin value = tx.getValue(wallet);
                final boolean sent = value.signum() < 0;
                final boolean showFee = sent && fee != null && !fee.isZero();

                txCache = new TransactionCacheEntry(value, sent, showFee);
                transactionCache.put(tx.getTxId(), txCache);
            }

            //
            // Assign the colors of text and values
            //
            final int primaryStatusColor, secondaryStatusColor, valueColor;
            if (confidence.hasErrors()) {
                primaryStatusColor = colorError;
                secondaryStatusColor = colorError;
                valueColor = colorError;
            } else {
                primaryStatusColor = colorPrimaryStatus;
                secondaryStatusColor = colorSecondaryStatus;
                valueColor = txCache.sent ? colorValueNegative : colorValuePositve;
            }

            //
            // Set the time. eg.  "<date> <time>"
            //
            final Date time = tx.getUpdateTime();
            timeView.setText(DateUtils.formatDateTime(itemView.getContext(), time.getTime(),
                    DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH | DateUtils.FORMAT_SHOW_TIME));

            //
            // Set primary status - Sent:  Sent, Masternode Special Tx's, Internal
            //                  Received:  Received, Mining Rewards, Masternode Rewards
            //
            int idPrimaryStatus = TransactionUtil.getTransactionTypeName(tx, wallet);
            primaryStatusView.setText(idPrimaryStatus);
            primaryStatusView.setTextColor(primaryStatusColor);

            //
            // Set the value.  [signal] D [value]
            // signal is + or -, or not visible if the value is zero (internal or other special transactions)
            // D is the Dash Symbol
            // value has no sign.  It is zero for internal or other special transactions
            //
            valueView.setFormat(format);
            final Coin value;

            value = txCache.showFee ? txCache.value.add(fee) : txCache.value;

            valueView.setVisibility(View.VISIBLE);
            signalView.setVisibility(!value.isZero() ? View.VISIBLE : View.GONE);
            dashSymbolView.setVisibility(View.VISIBLE);
            valueView.setTextColor(valueColor);
            signalView.setTextColor(valueColor);
            dashSymbolView.setColorFilter(valueColor);

            if(value.isPositive()) {
                signalView.setText(String.format("%c", org.dash.wallet.common.Constants.CURRENCY_PLUS_SIGN));
                valueView.setAmount(value);
            } else if(value.isNegative()) {
                signalView.setText(String.format("%c", org.dash.wallet.common.Constants.CURRENCY_MINUS_SIGN));
                valueView.setAmount(value.negate());
            } else {
                valueView.setAmount(Coin.ZERO);
            }

            // fiat value
            if(!value.isZero()) {
                final ExchangeRate exchangeRate = tx.getExchangeRate();
                if(exchangeRate != null) {
                    String exchangeCurrencyCode = GenericUtils.currencySymbol(exchangeRate.fiat.currencyCode);
                    fiatView.setFiatAmount(txCache.value, exchangeRate, Constants.LOCAL_FORMAT,
                            exchangeCurrencyCode);
                    fiatView.setVisibility(View.VISIBLE);
                    rateNotAvailableView.setVisibility(View.GONE);
                } else {
                    fiatView.setVisibility(View.GONE);
                    rateNotAvailableView.setVisibility(View.VISIBLE);
                }
            } else {
                fiatView.setVisibility(View.GONE);
                rateNotAvailableView.setVisibility(View.GONE);
            }


            //
            // Show the secondary status:
            //
            int secondaryStatusId = -1;
            if(confidence.hasErrors())
                secondaryStatusId = TransactionUtil.getErrorName(tx);
            else if(!txCache.sent)
                secondaryStatusId = TransactionUtil.getReceivedStatusString(tx, wallet);

            if(secondaryStatusId != -1)
                secondaryStatusView.setText(secondaryStatusId);
            else secondaryStatusView.setText(null);
            secondaryStatusView.setTextColor(secondaryStatusColor);
        }

    }
}
