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
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.format.DateUtils;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.PopupMenu.OnMenuItemClickListener;
import android.widget.TextView;

import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.AsyncTaskLoader;
import androidx.loader.content.Loader;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Transaction.Purpose;
import org.bitcoinj.core.TransactionConfidence.ConfidenceType;
import org.bitcoinj.script.ScriptException;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.Wallet;
import org.dash.wallet.common.Configuration;
import org.dash.wallet.common.services.analytics.AnalyticsConstants;
import org.dash.wallet.common.services.analytics.FirebaseAnalyticsServiceImpl;
import org.dash.wallet.common.ui.BaseLockScreenFragment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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
import de.schildbach.wallet.data.BlockchainState;
import de.schildbach.wallet.util.BitmapFragment;
import de.schildbach.wallet.util.CrashReporter;
import de.schildbach.wallet.util.Qr;
import de.schildbach.wallet.util.ThrottlingWalletChangeListener;
import de.schildbach.wallet.util.WalletUtils;
import de.schildbach.wallet_test.R;

/**
 * @author Andreas Schildbach
 */
public class WalletTransactionsFragment extends BaseLockScreenFragment implements LoaderManager.LoaderCallbacks<List<Transaction>>,
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
    private TextView syncingText;
    private TransactionsFilterSharedViewModel transactionsFilterSharedViewModel;
    private RefreshUpdateShortcutsPaneViewModel refreshShortcutsPaneViewModel;

    @Nullable
    private Direction direction;

    private final Handler handler = new Handler();

    private static final int ID_TRANSACTION_LOADER = 0;

    private static final String ARG_DIRECTION = "direction";
    private static final long THROTTLE_MS = DateUtils.SECOND_IN_MILLIS;

    private static final int SHOW_QR_THRESHOLD_BYTES = 2500;
    private static final Logger log = LoggerFactory.getLogger(WalletTransactionsFragment.class);
    private static final FirebaseAnalyticsServiceImpl analytics =
            FirebaseAnalyticsServiceImpl.Companion.getInstance();

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

        adapter = new TransactionsAdapter(activity, wallet, application.maxConnectedPeers(), this);
        adapter.setShowTransactionRowMenu(true);

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
        view.findViewById(R.id.transaction_filter_btn).setOnClickListener(v -> {
            dialogFragment = new TransactionsFilterDialog();
            dialogFragment.show(getChildFragmentManager(), null);
        });

        refreshShortcutsPaneViewModel = new ViewModelProvider(requireActivity()).get(RefreshUpdateShortcutsPaneViewModel.class);

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

        AppDatabase.getAppDatabase().blockchainStateDao().load().observe(getViewLifecycleOwner(), new Observer<BlockchainState>() {
            @Override
            public void onChanged(de.schildbach.wallet.data.BlockchainState blockchainState) {
                updateSyncState(blockchainState);
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
    public void onTransactionMenuClick(final View view, final Transaction tx) {
        final boolean txSent = tx.getValue(wallet).signum() < 0;
        final Address txAddress = txSent ? WalletUtils.getToAddressOfSent(tx, wallet).get(0)
                : WalletUtils.getWalletAddressOfReceived(tx, wallet);
        final byte[] txSerialized = tx.unsafeBitcoinSerialize();
        final boolean txRotation = tx.getPurpose() == Purpose.KEY_ROTATION;

        Context wrapper = new ContextThemeWrapper(activity, R.style.My_PopupOverlay);
        final PopupMenu popupMenu = new PopupMenu(wrapper, view);
        popupMenu.inflate(R.menu.wallet_transactions_context);
        final MenuItem editAddressMenuItem = popupMenu.getMenu()
                .findItem(R.id.wallet_transactions_context_edit_address);
        if (!txRotation && txAddress != null) {
            editAddressMenuItem.setVisible(true);
            final boolean isAdd = AddressBookProvider.resolveLabel(activity, txAddress.toBase58()) == null;
            final boolean isOwn = wallet.isPubKeyHashMine(txAddress.getHash160());

            if (isOwn)
                editAddressMenuItem.setTitle(isAdd ? R.string.edit_address_book_entry_dialog_title_add_receive
                        : R.string.edit_address_book_entry_dialog_title_edit_receive);
            else
                editAddressMenuItem.setTitle(isAdd ? R.string.edit_address_book_entry_dialog_title_add
                        : R.string.edit_address_book_entry_dialog_title_edit);
        } else {
            editAddressMenuItem.setVisible(false);
        }

        popupMenu.getMenu().findItem(R.id.wallet_transactions_context_show_qr)
                .setVisible(!txRotation && txSerialized.length < SHOW_QR_THRESHOLD_BYTES);
        popupMenu.setOnMenuItemClickListener(new OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(final MenuItem item) {
                switch (item.getItemId()) {
                    case R.id.wallet_transactions_context_edit_address:
                        handleEditAddress(tx);
                        return true;

                    case R.id.wallet_transactions_context_show_qr:
                        handleShowQr();
                        return true;

                    case R.id.wallet_transactions_context_report_issue:
                        handleReportIssue(tx);
                        return true;

                    case R.id.wallet_transactions_context_browse:
                        if (activity instanceof WalletActivity) {
                            WalletUtils.viewOnBlockExplorer(getActivity(), tx.getPurpose(),
                                    tx.getHashAsString());
                        }
                        return true;
                }

                return false;
            }

            private void handleEditAddress(final Transaction tx) {
                EditAddressBookEntryFragment.edit(getFragmentManager(), txAddress);
            }

            private void handleShowQr() {
                final Bitmap qrCodeBitmap = Qr.bitmap(Qr.encodeCompressBinary(txSerialized));
                BitmapFragment.show(getFragmentManager(), qrCodeBitmap);
            }

            private void handleReportIssue(final Transaction tx) {
                final ReportIssueDialogBuilder dialog = new ReportIssueDialogBuilder(activity,
                        R.string.report_issue_dialog_title_transaction, R.string.report_issue_dialog_message_issue) {
                    @Override
                    protected CharSequence subject() {
                        return Constants.REPORT_SUBJECT_ISSUE + " " + application.packageInfo().versionName;
                    }

                    @Override
                    protected CharSequence collectApplicationInfo() throws IOException {
                        final StringBuilder applicationInfo = new StringBuilder();
                        CrashReporter.appendApplicationInfo(applicationInfo, application);
                        return applicationInfo;
                    }

                    @Override
                    protected CharSequence collectDeviceInfo() throws IOException {
                        final StringBuilder deviceInfo = new StringBuilder();
                        CrashReporter.appendDeviceInfo(deviceInfo, activity);
                        return deviceInfo;
                    }

                    @Override
                    protected CharSequence collectContextualData() {
                        final StringBuilder contextualData = new StringBuilder();
                        try {
                            contextualData.append(tx.getValue(wallet).toFriendlyString()).append(" total value");
                        } catch (final ScriptException x) {
                            contextualData.append(x.getMessage());
                        }
                        contextualData.append('\n');
                        if (tx.hasConfidence())
                            contextualData.append("  confidence: ").append(tx.getConfidence()).append('\n');
                        contextualData.append(tx.toString());
                        return contextualData;
                    }

                    @Override
                    protected CharSequence collectWalletDump() {
                        return application.getWallet().toString(false, true, true, null);
                    }
                };
                alertDialog = dialog.buildAlertDialog();
                alertDialog.show();
            }
        });
        popupMenu.show();
    }

    @Override
    public void onTransactionRowClicked(Transaction tx) {
        TransactionDetailsDialogFragment transactionDetailsDialogFragment =
                TransactionDetailsDialogFragment.newInstance(tx.getTxId());
        transactionDetailsDialogFragment.show(getChildFragmentManager(), null);
        analytics.logEvent(AnalyticsConstants.Home.TRANSACTION_DETAILS, Bundle.EMPTY);
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
            SpannableStringBuilder str = new SpannableStringBuilder(syncing + " " + percentage + "%");
            int start = syncing.length() + 1;
            int end = str.length();
            str.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            syncingText.setText(str);
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
        refreshShortcutsPaneViewModel.getOnTransactionsUpdated().call();
    }

    public boolean isHistoryEmpty() {
        return adapter.getItemCount() == 0;
    }
}
