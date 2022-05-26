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

package de.schildbach.wallet.ui.main;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewModelProvider;
import androidx.loader.content.Loader;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Transaction.Purpose;
import org.bitcoinj.core.TransactionConfidence.ConfidenceType;
import org.bitcoinj.wallet.Wallet;
import org.dash.wallet.common.Configuration;
import org.dash.wallet.common.services.analytics.AnalyticsConstants;
import org.dash.wallet.common.ui.BaseLockScreenFragment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import de.schildbach.wallet.AppDatabase;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.data.AddressBookProvider;
import de.schildbach.wallet.data.BlockchainState;
import de.schildbach.wallet.ui.AbstractWalletActivity;
import de.schildbach.wallet.ui.TransactionDetailsDialogFragment;
import de.schildbach.wallet.ui.TransactionsAdapter;
import de.schildbach.wallet.ui.TransactionsFilterDialog;
import de.schildbach.wallet.ui.TransactionsFilterSharedViewModel;
import de.schildbach.wallet_test.R;
import kotlin.Unit;
import kotlin.time.ExperimentalTime;
import kotlinx.coroutines.ExperimentalCoroutinesApi;
import kotlinx.coroutines.FlowPreview;

/**
 * @author Andreas Schildbach
 */
@FlowPreview
@ExperimentalTime
@ExperimentalCoroutinesApi
public class WalletTransactionsFragment extends BaseLockScreenFragment implements
        TransactionsAdapter.OnClickListener, OnSharedPreferenceChangeListener {


    public enum Direction {
        RECEIVED, SENT;
    }

    private AbstractWalletActivity activity;

    private WalletApplication application;
    private Configuration config;
    private Wallet wallet;
    private ContentResolver resolver;

    private TextView emptyView;
    private View loading;
    private RecyclerView recyclerView;
    private TransactionsAdapter adapter;
    private TextView syncingText;
    private TransactionsFilterSharedViewModel transactionsFilterSharedViewModel;
    private MainViewModel mainViewModel;

    @Nullable
    private Direction direction;

    private final Handler handler = new Handler();

    private static final String ARG_DIRECTION = "direction";

    private final ContentObserver addressBookObserver = new ContentObserver(handler) {
        @Override
        public void onChange(final boolean selfChange) {
            adapter.clearCacheAndNotifyDataSetChanged();
        }
    };

    @Override
    public void onAttach(final Activity activity) {
        super.onAttach(activity);

        this.activity = (AbstractWalletActivity) activity;
        this.application = (WalletApplication) activity.getApplication();
        this.config = application.getConfiguration();
        this.wallet = application.getWallet();
        this.resolver = activity.getContentResolver();
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        adapter = new TransactionsAdapter(activity, wallet, this);
        this.direction = null;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        recyclerView.setAdapter(null);
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container,
                             final Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.wallet_transactions_fragment, container, false);

        emptyView = view.findViewById(R.id.wallet_transactions_empty);
        loading = view.findViewById(R.id.loading);
        syncingText = view.findViewById(R.id.syncing);
        transactionsFilterSharedViewModel = new ViewModelProvider(requireActivity())
                .get(TransactionsFilterSharedViewModel.class);
        mainViewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);

        view.findViewById(R.id.transaction_filter_btn).setOnClickListener(v -> {
            dialogFragment = new TransactionsFilterDialog();
            dialogFragment.show(getChildFragmentManager(), null);
        });

        recyclerView = view.findViewById(R.id.wallet_transactions_list);
        recyclerView.setHasFixedSize(true);
        recyclerView.setLayoutManager(new LinearLayoutManager(activity));
        recyclerView.setAdapter(adapter);
        recyclerView.addItemDecoration(new RecyclerView.ItemDecoration() {
            private final int PADDING = 2
                    * activity.getResources().getDimensionPixelOffset(R.dimen.card_padding_vertical);

            @Override
            public void getItemOffsets(@NonNull final Rect outRect, @NonNull final View view, @NonNull final RecyclerView parent,
                                       @NonNull final RecyclerView.State state) {
                super.getItemOffsets(outRect, view, parent, state);

                final int position = parent.getChildAdapterPosition(view);
                if (position == 0)
                    outRect.top += PADDING;
                else if (position == parent.getAdapter().getItemCount() - 1)
                    outRect.bottom += PADDING;
            }
        });

        LifecycleOwner lifecycleOwner = getViewLifecycleOwner();
        transactionsFilterSharedViewModel.getOnAllTransactionsSelected().observe(lifecycleOwner, aVoid -> {
            direction = null;
            reloadTransactions();
        });
        transactionsFilterSharedViewModel.getOnReceivedTransactionsSelected().observe(lifecycleOwner, aVoid -> {
            direction = Direction.RECEIVED;
            reloadTransactions();
        });
        transactionsFilterSharedViewModel.getOnSentTransactionsSelected().observe(lifecycleOwner, aVoid -> {
            direction = Direction.SENT;
            reloadTransactions();
        });

        // TODO
        AppDatabase.getAppDatabase().blockchainStateDao().load().observe(getViewLifecycleOwner(), this::updateSyncState);

        mainViewModel.getTransactions().observe(lifecycleOwner, wrappedTransactions -> {
            loading.setVisibility(View.GONE);
            adapter.replace(wrappedTransactions);
            updateView();

            if (wrappedTransactions.isEmpty()) {
                showEmptyView();
            } else {
                showTransactionList();
            }
        });

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();

        resolver.registerContentObserver(AddressBookProvider.contentUri(activity.getPackageName()), true,
                addressBookObserver);

        config.registerOnSharedPreferenceChangeListener(this);

        final Bundle args = new Bundle();
        args.putSerializable(ARG_DIRECTION, direction);

        updateView();
    }

    @Override
    public void onPause() {
        config.unregisterOnSharedPreferenceChangeListener(this);

        resolver.unregisterContentObserver(addressBookObserver);

        super.onPause();
    }

    private void reloadTransactions() {
        final Bundle args = new Bundle();
        args.putSerializable(ARG_DIRECTION, direction);
//        loaderManager.restartLoader(ID_TRANSACTION_LOADER, args, this);
    }

    @Override
    public void onTransactionRowClicked(Transaction tx) {
        TransactionDetailsDialogFragment transactionDetailsDialogFragment =
                TransactionDetailsDialogFragment.newInstance(tx.getTxId());
        transactionDetailsDialogFragment.show(getParentFragmentManager(), null);
        mainViewModel.logEvent(AnalyticsConstants.Home.TRANSACTION_DETAILS);
    }

//    args.getSerializable(ARG_DIRECTION)

    private void updateSyncState(BlockchainState blockchainState) {
        if (blockchainState == null) {
            return;
        }

        int percentage = blockchainState.getPercentageSync();
        if (blockchainState.getReplaying() && blockchainState.getPercentageSync() == 100) {
            //This is to prevent showing 100% when using the Rescan blockchain function.
            //The first few broadcasted blockchainStates are with percentage sync at 100%
            percentage = 0;
        }

        if (blockchainState.isSynced()) {
            syncingText.setVisibility(View.GONE);
        } else {
            syncingText.setVisibility(View.VISIBLE);
            String syncing = getString(R.string.syncing);

            if (percentage == 0) {
                syncing += "…";
                syncingText.setText(syncing);
            } else {
                SpannableStringBuilder str = new SpannableStringBuilder(syncing + " " + percentage + "%");
                int start = syncing.length() + 1;
                int end = str.length();
                str.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                syncingText.setText(str);
            }
        }
    }

    private void showTransactionList() {
        emptyView.setVisibility(View.GONE);
        recyclerView.setVisibility(View.VISIBLE);
    }

    private void showEmptyView() {
        emptyView.setVisibility(View.VISIBLE);
        recyclerView.setVisibility(View.INVISIBLE);
    }

    public void onLoaderReset(final Loader<List<Transaction>> loader) {
        // don't clear the adapter, because it will confuse users
    }

    // TODO: filter and sort
    public List<Transaction> loadInBackground() {
        org.bitcoinj.core.Context.propagate(Constants.CONTEXT);

        final Set<Transaction> transactions = wallet.getTransactions(true);
        final List<Transaction> filteredTransactions = new ArrayList<>(transactions.size());

        for (final Transaction tx : transactions) {
            final boolean sent = tx.getValue(wallet).signum() < 0;
            final boolean isInternal = tx.getPurpose() == Purpose.KEY_ROTATION;

            if ((direction == Direction.RECEIVED && !sent && !isInternal) || direction == null
                    || (direction == Direction.SENT && sent && !isInternal))
                filteredTransactions.add(tx);
        }

        Collections.sort(filteredTransactions, TRANSACTION_COMPARATOR);

        return filteredTransactions;
    }

    private static final Comparator<Transaction> TRANSACTION_COMPARATOR = (tx1, tx2) -> {
        final boolean pending1 = tx1.getConfidence().getConfidenceType() == ConfidenceType.PENDING;
        final boolean pending2 = tx2.getConfidence().getConfidenceType() == ConfidenceType.PENDING;

        if (pending1 != pending2)
            return pending1 ? -1 : 1;

        final Date updateTime1 = tx1.getUpdateTime();
        final long time1 = updateTime1 != null ? updateTime1.getTime() : 0;
        final Date updateTime2 = tx2.getUpdateTime();
        final long time2 = updateTime2 != null ? updateTime2.getTime() : 0;

        if (time1 != time2)
            return time1 > time2 ? -1 : 1;

        return tx1.getHash().compareTo(tx2.getHash());
    };

    @Override
    public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences, final String key) {
        if (Configuration.PREFS_KEY_BTC_PRECISION.equals(key) || Configuration.PREFS_KEY_REMIND_BACKUP.equals(key) ||
                Configuration.PREFS_KEY_REMIND_BACKUP_SEED.equals(key))
            updateView();
    }

    private void updateView() {
        adapter.setFormat(config.getFormat());
        mainViewModel.getOnTransactionsUpdated().call(Unit.INSTANCE);
    }

    public boolean isHistoryEmpty() {
        return adapter.getItemCount() == 0;
    }
}
