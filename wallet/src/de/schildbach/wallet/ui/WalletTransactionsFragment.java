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
import android.content.ContentResolver;
import android.content.Intent;
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

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.wallet.Wallet;
import org.dash.wallet.common.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import de.schildbach.wallet.AppDatabase;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.data.AddressBookProvider;
import de.schildbach.wallet.data.BlockchainIdentityBaseData;
import de.schildbach.wallet.data.BlockchainIdentityData;
import de.schildbach.wallet.data.DashPayProfile;
import de.schildbach.wallet.ui.dashpay.CreateIdentityService;
import de.schildbach.wallet.ui.dashpay.TransactionsViewModel;
import de.schildbach.wallet_test.R;
import kotlin.Pair;

/**
 * @author Andreas Schildbach
 */
public class WalletTransactionsFragment extends Fragment implements TransactionsAdapter.OnClickListener,
        OnSharedPreferenceChangeListener {


    public enum Direction {
        RECEIVED, SENT
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
    private Spinner filterSpinner;

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
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setRetainInstance(true);

        adapter = new TransactionsAdapter(activity, wallet, application.maxConnectedPeers(), this);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        recyclerView.setAdapter(null);
    }

    @Override
    public void onViewCreated(@NonNull View view, @androidx.annotation.Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        final TransactionsViewModel transactionsViewModel = new ViewModelProvider(requireActivity()).get(TransactionsViewModel.class);
        transactionsViewModel.getTransactionsLiveData().observe(getViewLifecycleOwner(), new Observer<Pair<List<Transaction>,
                        Map<Sha256Hash, DashPayProfile>>>() {
            @Override
            public void onChanged(Pair<List<Transaction>, Map<Sha256Hash, DashPayProfile>> data) {
                List<Transaction> transactions = data.component1();
                Map<Sha256Hash, DashPayProfile> contactsByTransaction = data.component2();
                loading.setVisibility(View.GONE);
                WalletTransactionsFragment.this.adapter.replace(transactions, contactsByTransaction);
                updateView();
                if (transactions.isEmpty()) {
                    showEmptyView();
                } else {
                    showTransactionList();
                }
            }
        });


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
                        transactionsViewModel.getDirection().setValue(null);
                        break;
                    case 1:
                        transactionsViewModel.getDirection().setValue(TransactionsViewModel.Direction.RECEIVED);
                        break;
                    case 2:
                        transactionsViewModel.getDirection().setValue(TransactionsViewModel.Direction.SENT);
                        break;
                }
                //TODO: Add loading on TransactionsViewModel. (Resource.loading)
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

        AppDatabase.getAppDatabase().blockchainIdentityDataDaoAsync().loadBase().observe(getViewLifecycleOwner(), new Observer<BlockchainIdentityBaseData>() {
            @Override
            public void onChanged(BlockchainIdentityBaseData blockchainIdentityData) {
                if (blockchainIdentityData != null) {
                    WalletTransactionsFragment.this.adapter.setBlockchainIdentityData(blockchainIdentityData);
                }
            }
        });

        resolver.registerContentObserver(AddressBookProvider.contentUri(activity.getPackageName()), true,
                addressBookObserver);

        config.registerOnSharedPreferenceChangeListener(this);
        updateView();

    }

    @Override
    public void onDestroy() {
        config.unregisterOnSharedPreferenceChangeListener(this);

        resolver.unregisterContentObserver(addressBookObserver);
        super.onDestroy();
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container,
                             final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.wallet_transactions_fragment, container, false);
    }

    @Override
    public void onTransactionRowClicked(Transaction tx) {
        TransactionDetailsDialogFragment transactionDetailsDialogFragment =
                TransactionDetailsDialogFragment.newInstance(tx.getTxId());
        requireActivity().getSupportFragmentManager().beginTransaction()
                .add(transactionDetailsDialogFragment, null).commitAllowingStateLoss();
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

    private void showTransactionList() {
        emptyView.setVisibility(View.GONE);
        recyclerView.setVisibility(View.VISIBLE);
    }

    private void showEmptyView() {
        emptyView.setVisibility(View.VISIBLE);
        recyclerView.setVisibility(View.INVISIBLE);
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
