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

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.AsyncTaskLoader;
import androidx.loader.content.Loader;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Transaction.Purpose;
import org.bitcoinj.core.TransactionConfidence.ConfidenceType;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.Wallet;
import org.dash.wallet.common.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

import javax.annotation.Nullable;

import de.schildbach.wallet.AppDatabase;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.data.AddressBookProvider;
import de.schildbach.wallet.data.BlockchainIdentityBaseData;
import de.schildbach.wallet.data.BlockchainIdentityData;
import de.schildbach.wallet.ui.dashpay.CreateIdentityService;
import de.schildbach.wallet.util.ThrottlingWalletChangeListener;
import de.schildbach.wallet_test.R;

/**
 * @author Andreas Schildbach
 */
public class WalletTransactionsFragment extends Fragment implements LoaderManager.LoaderCallbacks<List<Transaction>>,
        TransactionsAdapter.OnClickListener, OnSharedPreferenceChangeListener {


    public enum Direction {
        RECEIVED, SENT;
    }

    private AbstractWalletActivity activity;

    private WalletApplication application;
    private Configuration config;
    private Wallet wallet;
    private ContentResolver resolver;
    private LoaderManager loaderManager;

    private TextView emptyView;
    private View loading;
    private RecyclerView recyclerView;
    private TransactionsAdapter adapter;
    private Spinner filterSpinner;

    @Nullable
    private Direction direction;

    private final Handler handler = new Handler();

    private static final int ID_TRANSACTION_LOADER = 0;

    private static final String ARG_DIRECTION = "direction";
    private static final long THROTTLE_MS = DateUtils.SECOND_IN_MILLIS;

    private static final int SHOW_QR_THRESHOLD_BYTES = 2500;
    private static final Logger log = LoggerFactory.getLogger(WalletTransactionsFragment.class);

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
        this.loaderManager = LoaderManager.getInstance(this.activity);
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setRetainInstance(true);

        adapter = new TransactionsAdapter(activity, wallet, application.maxConnectedPeers(), this);

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
        filterSpinner = view.findViewById(R.id.history_filter);

        recyclerView = view.findViewById(R.id.wallet_transactions_list);
        recyclerView.setHasFixedSize(true);
        recyclerView.setLayoutManager(new LinearLayoutManager(activity));
        recyclerView.setAdapter(adapter);
        recyclerView.addItemDecoration(new RecyclerView.ItemDecoration() {
            private final int PADDING = 2
                    * activity.getResources().getDimensionPixelOffset(R.dimen.card_padding_vertical);

            @Override
            public void getItemOffsets(final Rect outRect, final View view, final RecyclerView parent,
                                       final RecyclerView.State state) {
                super.getItemOffsets(outRect, view, parent, state);

                final int position = parent.getChildAdapterPosition(view);
                if (position == 0)
                    outRect.top += PADDING;
                else if (position == parent.getAdapter().getItemCount() - 1)
                    outRect.bottom += PADDING;
            }
        });

        final ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(filterSpinner.getContext(), R.array.history_filter, R.layout.custom_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        filterSpinner.setAdapter(adapter);
        filterSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                switch (position) {
                    case 0:
                        direction = null;
                        break;
                    case 1:
                        direction = Direction.RECEIVED;
                        break;
                    case 2:
                        direction = Direction.SENT;
                        break;
                }
                reloadTransactions();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

        AppDatabase.getAppDatabase().blockchainIdentityDataDao().loadBase().observe(getViewLifecycleOwner(), new Observer<BlockchainIdentityBaseData>() {
            @Override
            public void onChanged(BlockchainIdentityBaseData blockchainIdentityData) {
                if (blockchainIdentityData != null) {
                    WalletTransactionsFragment.this.adapter.setBlockchainIdentityData(blockchainIdentityData);
                }
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
        loaderManager.initLoader(ID_TRANSACTION_LOADER, args, this);

        wallet.addCoinsReceivedEventListener(Threading.SAME_THREAD, transactionChangeListener);
        wallet.addCoinsSentEventListener(Threading.SAME_THREAD, transactionChangeListener);
        wallet.addChangeEventListener(Threading.SAME_THREAD, transactionChangeListener);
        wallet.addTransactionConfidenceEventListener(Threading.SAME_THREAD, transactionChangeListener);

        updateView();
    }

    @Override
    public void onPause() {
        wallet.removeTransactionConfidenceEventListener(transactionChangeListener);
        wallet.removeChangeEventListener(transactionChangeListener);
        wallet.removeCoinsSentEventListener(transactionChangeListener);
        wallet.removeCoinsReceivedEventListener(transactionChangeListener);
        transactionChangeListener.removeCallbacks();

        loaderManager.destroyLoader(ID_TRANSACTION_LOADER);

        config.unregisterOnSharedPreferenceChangeListener(this);

        resolver.unregisterContentObserver(addressBookObserver);

        super.onPause();
    }

    private void reloadTransactions() {
        final Bundle args = new Bundle();
        args.putSerializable(ARG_DIRECTION, direction);
        loaderManager.restartLoader(ID_TRANSACTION_LOADER, args, this);
    }

    @Override
    public void onTransactionRowClicked(Transaction tx) {
        TransactionDetailsDialogFragment transactionDetailsDialogFragment =
                TransactionDetailsDialogFragment.newInstance(tx.getTxId());
        transactionDetailsDialogFragment.show(getChildFragmentManager(), null);
    }

    @Override
    public void onProcessingIdentityRowClicked(final BlockchainIdentityBaseData blockchainIdentityData, boolean retry) {
        if (retry) {
            activity.startService(CreateIdentityService.createIntentForRetry(activity, false));
        } else {
            if (blockchainIdentityData.getCreationStateErrorMessage() != null) {
                if (blockchainIdentityData.getCreationState() == BlockchainIdentityData.CreationState.USERNAME_REGISTERING) {
                    startActivity(CreateUsernameActivity.createIntentReuseTransaction(activity));
                } else {
                    Toast.makeText(getContext(), blockchainIdentityData.getCreationStateErrorMessage(), Toast.LENGTH_LONG).show();
                }
            } else if (blockchainIdentityData.getCreationState() == BlockchainIdentityData.CreationState.DONE) {
                //startActivity(CreateUsernameActivity.createIntent(activity, blockchainIdentityData.getUsername()));
                startActivity(new Intent(activity, SearchUserActivity.class));
            }
        }
    }

    @Override
    public Loader<List<Transaction>> onCreateLoader(final int id, final Bundle args) {
        return new TransactionsLoader(activity, wallet, (Direction) args.getSerializable(ARG_DIRECTION));
    }

    @Override
    public void onLoadFinished(final Loader<List<Transaction>> loader, final List<Transaction> transactions) {
        final Direction direction = ((TransactionsLoader) loader).getDirection();

        loading.setVisibility(View.GONE);
        adapter.replace(transactions);
        updateView();

        if (transactions.isEmpty()) {
            showEmptyView();
        } else {
            showTransactionList();
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

    @Override
    public void onLoaderReset(final Loader<List<Transaction>> loader) {
        // don't clear the adapter, because it will confuse users
    }

    private final ThrottlingWalletChangeListener transactionChangeListener = new ThrottlingWalletChangeListener(
            THROTTLE_MS) {
        @Override
        public void onThrottledWalletChanged() {
            adapter.notifyDataSetChanged();
        }
    };

    private static class TransactionsLoader extends AsyncTaskLoader<List<Transaction>> {

        private LocalBroadcastManager broadcastManager;
        private final Wallet wallet;
        @Nullable
        private final Direction direction;

        private TransactionsLoader(final Context context, final Wallet wallet, @Nullable final Direction direction) {
            super(context);

            this.broadcastManager = LocalBroadcastManager.getInstance(context.getApplicationContext());
            this.wallet = wallet;
            this.direction = direction;
        }

        public @Nullable
        Direction getDirection() {
            return direction;
        }

        @Override
        protected void onStartLoading() {
            super.onStartLoading();

            wallet.addCoinsReceivedEventListener(Threading.SAME_THREAD, transactionAddRemoveListener);
            wallet.addCoinsSentEventListener(Threading.SAME_THREAD, transactionAddRemoveListener);
            wallet.addChangeEventListener(Threading.SAME_THREAD, transactionAddRemoveListener);
            broadcastManager.registerReceiver(walletChangeReceiver,
                    new IntentFilter(WalletApplication.ACTION_WALLET_REFERENCE_CHANGED));
            transactionAddRemoveListener.onReorganize(null); // trigger at least one reload

            safeForceLoad();
        }

        @Override
        protected void onStopLoading() {
            broadcastManager.unregisterReceiver(walletChangeReceiver);
            wallet.removeChangeEventListener(transactionAddRemoveListener);
            wallet.removeCoinsSentEventListener(transactionAddRemoveListener);
            wallet.removeCoinsReceivedEventListener(transactionAddRemoveListener);
            transactionAddRemoveListener.removeCallbacks();

            super.onStopLoading();
        }

        @Override
        protected void onReset() {
            broadcastManager.unregisterReceiver(walletChangeReceiver);
            wallet.removeChangeEventListener(transactionAddRemoveListener);
            wallet.removeCoinsSentEventListener(transactionAddRemoveListener);
            wallet.removeCoinsReceivedEventListener(transactionAddRemoveListener);
            transactionAddRemoveListener.removeCallbacks();

            super.onReset();
        }

        @Override
        public List<Transaction> loadInBackground() {
            org.bitcoinj.core.Context.propagate(Constants.CONTEXT);

            final Set<Transaction> transactions = wallet.getTransactions(true);
            final List<Transaction> filteredTransactions = new ArrayList<Transaction>(transactions.size());

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

        private final ThrottlingWalletChangeListener transactionAddRemoveListener = new ThrottlingWalletChangeListener(
                THROTTLE_MS, true, true, false) {
            @Override
            public void onThrottledWalletChanged() {
                safeForceLoad();
            }
        };

        private final BroadcastReceiver walletChangeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(final Context context, final Intent intent) {
                safeForceLoad();
            }
        };

        private void safeForceLoad() {
            try {
                forceLoad();
            } catch (final RejectedExecutionException x) {
                log.info("rejected execution: " + TransactionsLoader.this.toString());
            }
        }

        private static final Comparator<Transaction> TRANSACTION_COMPARATOR = new Comparator<Transaction>() {
            @Override
            public int compare(final Transaction tx1, final Transaction tx2) {
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
            }
        };

    }

    @Override
    public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences, final String key) {
        if (Configuration.PREFS_KEY_BTC_PRECISION.equals(key) || Configuration.PREFS_KEY_REMIND_BACKUP.equals(key) ||
                Configuration.PREFS_KEY_REMIND_BACKUP_SEED.equals(key))
            updateView();
    }

    private void updateView() {
        adapter.setFormat(config.getFormat());
    }

    public boolean isHistoryEmpty() {
        return adapter.getItemCount() == 0;
    }

}
