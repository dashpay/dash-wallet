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

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionConfidence;
import org.bitcoinj.utils.ExchangeRate;
import org.bitcoinj.utils.MonetaryFormat;
import org.bitcoinj.wallet.Wallet;
import org.dash.wallet.common.ui.CurrencyTextView;
import org.dash.wallet.common.util.GenericUtils;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import de.schildbach.wallet.Constants;
import de.schildbach.wallet.data.AddressBookProvider;
import de.schildbach.wallet.data.BlockchainIdentityBaseData;
import de.schildbach.wallet.data.BlockchainIdentityData;
import de.schildbach.wallet.data.DashPayProfile;
import de.schildbach.wallet.ui.dashpay.ProcessingIdentityViewHolder;
import de.schildbach.wallet.ui.dashpay.utils.ProfilePictureDisplay;
import de.schildbach.wallet.util.TransactionUtil;
import de.schildbach.wallet.util.WalletUtils;
import de.schildbach.wallet_test.R;

/**
 * @author Andreas Schildbach
 */
public class TransactionsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> implements TransactionsHeaderViewHolder.OnFilterListener {

    private static final String PREFS_FILE_NAME = TransactionsAdapter.class.getSimpleName() + ".prefs";
    private static final String PREFS_KEY_HIDE_HELLO_CARD = "hide_hello_card";
    private static final String PREFS_KEY_HIDE_JOIN_DASHPAY_CARD = "hide_join_dashpay_card";

    private final SharedPreferences preferences;

    private final Context context;
    private final LayoutInflater inflater;

    private final Wallet wallet;
    @Nullable
    private final OnClickListener onClickListener;

    private final List<TransactionHistoryItem> transactions = new ArrayList<>();
    private final List<TransactionHistoryItem> filteredTransactions = new ArrayList<>();

    private MonetaryFormat format;

    private long selectedItemId = RecyclerView.NO_ID;

    private final int colorBackground, colorBackgroundSelected;
    private final int colorPrimaryStatus, colorSecondaryStatus, colorInsignificant;
    private final int colorValuePositve, colorValueNegative;
    private final int colorError;

    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_TRANSACTION = 1;
    private static final int VIEW_TYPE_PROCESSING_IDENTITY = 2;
    private static final int VIEW_TYPE_JOIN_DASHPAY = 3;

    private Map<Sha256Hash, TransactionCacheEntry> transactionCache = new HashMap<>();

    //Temporary field while IdentityCreationTx (or whatever we call it) is not integrated yet.
    private BlockchainIdentityBaseData blockchainIdentityData;

    private TransactionsHeaderViewHolder.Filter filter = TransactionsHeaderViewHolder.Filter.ALL;
    private boolean canJoinDashPay;
    private int joinDashPayItemId = "JOIN_DASHPAY".hashCode();

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
                               final int maxConnectedPeers,
                               final @Nullable OnClickListener onClickListener) {
        this.context = context;
        inflater = LayoutInflater.from(context);

        this.preferences = getPreferences(context);

        this.wallet = wallet;
        this.onClickListener = onClickListener;

        final Resources res = context.getResources();
        colorBackground = res.getColor(R.color.bg_bright);
        colorBackgroundSelected = res.getColor(R.color.bg_panel);
        colorPrimaryStatus = res.getColor(R.color.primary_status);
        colorSecondaryStatus = res.getColor(R.color.secondary_status);
        colorInsignificant = res.getColor(R.color.fg_insignificant);
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
        filteredTransactions.clear();

        notifyDataSetChanged();
    }

    public void replace(final Collection<TransactionHistoryItem> transactions) {
        this.transactions.clear();
        this.transactions.addAll(transactions);
        filter();

        notifyDataSetChanged();
    }

    public void clearCacheAndNotifyDataSetChanged() {
        transactionCache.clear();

        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        int count = filteredTransactions.size() + 1;

        if (shouldShowHelloCard()) {
            count += 1;
        } else if (shouldShowJoinDashPay()) {
            count += 1;
        }

        return count;
    }

    @Override
    public long getItemId(int position) {
        if (position == RecyclerView.NO_POSITION)
            return RecyclerView.NO_ID;

        if (position == 0) {
            return 0;
        }

        if (shouldShowHelloCard()) {
            if (position == 1) {
                return blockchainIdentityData.getId();
            } else {
                return WalletUtils.longHash(filteredTransactions.get(position - 2).transaction.getTxId());
            }
        } else if (shouldShowJoinDashPay()) {
            if (position == 1) {
                return joinDashPayItemId;
            } else {
                return WalletUtils.longHash(filteredTransactions.get(position - 2).transaction.getTxId());
            }
        } else {
            return WalletUtils.longHash(filteredTransactions.get(position - 1).transaction.getTxId());
        }
    }

    @Override
    public int getItemViewType(final int position) {
        if (position == 0) {
            return VIEW_TYPE_HEADER;
        }
        if (shouldShowHelloCard() && position == 1) {
            return VIEW_TYPE_PROCESSING_IDENTITY;
        } else if (shouldShowJoinDashPay() && position == 1) {
            return VIEW_TYPE_JOIN_DASHPAY;
        }
        return VIEW_TYPE_TRANSACTION;
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(final ViewGroup parent, final int viewType) {
        if (viewType == VIEW_TYPE_PROCESSING_IDENTITY) {
            return new ProcessingIdentityViewHolder(inflater.inflate(R.layout.identity_creation_state, parent, false));
        } else if (viewType == VIEW_TYPE_TRANSACTION) {
            return new TransactionViewHolder(inflater.inflate(R.layout.transaction_row, parent, false));
        } else if (viewType == VIEW_TYPE_HEADER) {
            return new TransactionsHeaderViewHolder(inflater, parent, this);
        } else if (viewType == VIEW_TYPE_JOIN_DASHPAY) {
            return new JoinDashPayViewHolder(inflater, parent);
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

            TransactionHistoryItem transactionHistoryItem;
            if (shouldShowHelloCard()) {
                transactionHistoryItem = filteredTransactions.get(position - 2);
            } else if (shouldShowJoinDashPay()) {
                transactionHistoryItem = filteredTransactions.get(position - 2);
            } else {
                transactionHistoryItem = filteredTransactions.get(position - 1);
            }
            transactionHolder.bind(transactionHistoryItem);

            transactionHolder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(final View v) {
                    TransactionHistoryItem transactionHistoryItem;
                    int viewType = getItemViewType(1);
                    if (viewType == VIEW_TYPE_PROCESSING_IDENTITY || viewType == VIEW_TYPE_JOIN_DASHPAY) {
                        transactionHistoryItem = filteredTransactions.get(transactionHolder.getAdapterPosition() - 2);
                    } else {
                        transactionHistoryItem = filteredTransactions.get(transactionHolder.getAdapterPosition() - 1);
                    }

                    if (onClickListener != null) {
                        onClickListener.onTransactionRowClicked(transactionHistoryItem);
                    }
                }
            });
        } else if (holder instanceof ProcessingIdentityViewHolder) {
            ProcessingIdentityViewHolder processingIdentityHolder = ((ProcessingIdentityViewHolder) holder);
            processingIdentityHolder.bind(blockchainIdentityData, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (onClickListener != null) {
                        onClickListener.onProcessingIdentityRowClicked(blockchainIdentityData, true);
                    }
                }
            });
            processingIdentityHolder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(final View v) {
                    if (onClickListener != null) {
                        onClickListener.onProcessingIdentityRowClicked(blockchainIdentityData, false);

                        //hide "Hello Card" after first click
                        if (blockchainIdentityData.getCreationState() == BlockchainIdentityData.CreationState.DONE) {
                            preferences.edit().putBoolean(PREFS_KEY_HIDE_HELLO_CARD, true).apply();
                            notifyDataSetChanged();
                        }
                    }
                }
            });
        } else if (holder instanceof TransactionsHeaderViewHolder) {
            ((TransactionsHeaderViewHolder) holder).showEmptyState(filteredTransactions.size() == 0);
        } else if (holder instanceof JoinDashPayViewHolder) {
            ((JoinDashPayViewHolder) holder).bind(v -> {
                if (onClickListener != null) {
                    onClickListener.onJoinDashPayClicked();
                    preferences.edit().putBoolean(PREFS_KEY_HIDE_JOIN_DASHPAY_CARD, true).apply();
                    notifyDataSetChanged();
                }
            });
        }
    }

    public interface OnClickListener {

        void onTransactionRowClicked(TransactionHistoryItem transactionHistoryItem);

        void onProcessingIdentityRowClicked(BlockchainIdentityBaseData blockchainIdentityData, boolean retry);

        void onJoinDashPayClicked();

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
        private final ImageView icon;

        private TransactionViewHolder(final View itemView) {
            super(itemView);
            primaryStatusView = (TextView) itemView.findViewById(R.id.transaction_row_primary_status);
            secondaryStatusView = (TextView) itemView.findViewById(R.id.transaction_row_secondary_status);

            timeView = (TextView) itemView.findViewById(R.id.transaction_row_time);
            dashSymbolView = (ImageView) itemView.findViewById(R.id.dash_amount_symbol);
            valueView = (CurrencyTextView) itemView.findViewById(R.id.transaction_row_value);
            signalView = (TextView) itemView.findViewById(R.id.transaction_amount_signal);

            fiatView = (CurrencyTextView) itemView.findViewById(R.id.transaction_row_fiat);
            fiatView.setApplyMarkup(false);
            rateNotAvailableView = (TextView) itemView.findViewById(R.id.transaction_row_rate_not_available);

            icon = itemView.findViewById(R.id.icon);
        }

        private void bind(final TransactionHistoryItem transactionHistoryItem) {
            if (itemView instanceof CardView)
                ((CardView) itemView)
                        .setCardBackgroundColor(itemView.isActivated() ? colorBackgroundSelected : colorBackground);

            final Transaction tx = transactionHistoryItem.transaction;

            final TransactionConfidence confidence = tx.getConfidence();
            final Coin fee = tx.getFee();

            final TransactionConfidence.IXType ixStatus = confidence.getIXType();

            TransactionCacheEntry txCache = transactionCache.get(tx.getTxId());
            if (txCache == null) {
                final Coin value = tx.getValue(wallet);
                final boolean sent = value.signum() < 0;
                final boolean self = WalletUtils.isEntirelySelf(tx, wallet);
                final boolean showFee = sent && fee != null && !fee.isZero();
                final Address address;
                if (sent) {
                    List<Address> addresses = WalletUtils.getToAddressOfSent(tx, wallet);
                    address = addresses.isEmpty() ? null : addresses.get(0);
                } else {
                    address = WalletUtils.getWalletAddressOfReceived(tx, wallet);
                }
                final String addressLabel = address != null
                        ? AddressBookProvider.resolveLabel(context, address.toBase58()) : null;

                final Transaction.Type txType = tx.getType();

                txCache = new TransactionCacheEntry(value, sent, self, showFee, address, addressLabel, txType);
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
            timeView.setText(WalletUtils.formatDate(time.getTime()));

            //
            // Set primary status - Sent:  Sent, Masternode Special Tx's, Internal
            //                  Received:  Received, Mining Rewards, Masternode Rewards
            //
            final DashPayProfile contact = transactionHistoryItem.dashPayProfile;
            if (contact == null) {
                int idPrimaryStatus = TransactionUtil.getTransactionTypeName(tx, wallet);
                primaryStatusView.setText(idPrimaryStatus);
                icon.setImageResource(R.drawable.ic_dash_round);
                icon.setOnClickListener(null);
            } else {
                String name = "";
                if (contact.getDisplayName().isEmpty()) {
                    name = contact.getUsername();
                } else {
                    name = contact.getDisplayName();
                }
                primaryStatusView.setText(name);

                ProfilePictureDisplay.display(icon, contact.getAvatarUrl(), contact.getUsername());
                icon.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        context.startActivity(DashPayUserActivity.createIntent(context, contact));
                    }
                });
            }
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

            if (value.isPositive()) {
                signalView.setText(String.format("%c", org.dash.wallet.common.Constants.CURRENCY_PLUS_SIGN));
                valueView.setAmount(value);
            } else if (value.isNegative()) {
                signalView.setText(String.format("%c", org.dash.wallet.common.Constants.CURRENCY_MINUS_SIGN));
                valueView.setAmount(value.negate());
            } else {
                valueView.setAmount(Coin.ZERO);
            }

            // fiat value
            if (!value.isZero()) {
                final ExchangeRate exchangeRate = tx.getExchangeRate();
                if (exchangeRate != null) {
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
            if (confidence.hasErrors())
                secondaryStatusId = TransactionUtil.getErrorName(tx);
            else if (!txCache.sent)
                secondaryStatusId = TransactionUtil.getReceivedStatusString(tx, wallet);

            if (secondaryStatusId != -1)
                secondaryStatusView.setText(secondaryStatusId);
            else secondaryStatusView.setText(null);
            secondaryStatusView.setTextColor(secondaryStatusColor);
        }

    }

    public BlockchainIdentityBaseData getBlockchainIdentityData() {
        return blockchainIdentityData;
    }

    public void setBlockchainIdentityData(BlockchainIdentityBaseData blockchainIdentityData) {
        this.blockchainIdentityData = blockchainIdentityData;
        notifyDataSetChanged();
    }

    private boolean shouldShowHelloCard() {
        boolean hideHelloCard = preferences.getBoolean(PREFS_KEY_HIDE_HELLO_CARD, false);
        return blockchainIdentityData != null && !hideHelloCard &&
                blockchainIdentityData.getCreationState() != BlockchainIdentityData.CreationState.DONE_AND_DISMISS;
    }

    private boolean shouldShowJoinDashPay() {
        boolean hideJoinDashPay = preferences.getBoolean(PREFS_KEY_HIDE_JOIN_DASHPAY_CARD, false);
        return blockchainIdentityData == null && canJoinDashPay && !hideJoinDashPay;
    }

    public static void resetPreferences(Context context) {
        getPreferences(context).edit().clear().apply();
    }

    public static SharedPreferences getPreferences(Context context) {
        return context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE);
    }

    @Override
    public void onFilter(@NotNull TransactionsHeaderViewHolder.Filter filter) {
        this.filter = filter;
        filter();
    }

    private void filter() {
        final List<TransactionHistoryItem> resultTransactions = new ArrayList<>();
        for (TransactionHistoryItem transactionHistoryItem : transactions) {
            Transaction tx = transactionHistoryItem.transaction;
            boolean sent = tx.getValue(wallet).signum() < 0;
            boolean isInternal = tx.getPurpose() == Transaction.Purpose.KEY_ROTATION;

            if ((filter == TransactionsHeaderViewHolder.Filter.INCOMING && !sent && !isInternal)
                    || filter == TransactionsHeaderViewHolder.Filter.ALL
                    || (filter == TransactionsHeaderViewHolder.Filter.OUTGOING && sent && !isInternal)) {
                resultTransactions.add(transactionHistoryItem);
            }
        }
        filteredTransactions.clear();
        filteredTransactions.addAll(resultTransactions);
        notifyDataSetChanged();
    }

    public static class TransactionHistoryItem {

        private final Transaction transaction;
        private final DashPayProfile dashPayProfile;

        public TransactionHistoryItem(Transaction transaction, DashPayProfile dashPayProfile) {
            this.transaction = transaction;
            this.dashPayProfile = dashPayProfile;
        }

        public Transaction getTransaction() {
            return transaction;
        }

        public DashPayProfile getDashPayProfile() {
            return dashPayProfile;
        }
    }

    public void setCanJoinDashPay(boolean canJoinDashPay) {
        this.canJoinDashPay = canJoinDashPay;
        notifyDataSetChanged();
    }

}
