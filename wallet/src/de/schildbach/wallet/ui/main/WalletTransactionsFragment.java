/*
 * Copyright 2020 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui.main;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
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
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.bitcoinj.wallet.Wallet;
import org.dash.wallet.common.Configuration;
import org.dash.wallet.common.services.analytics.AnalyticsConstants;
import org.dash.wallet.common.services.analytics.AnalyticsService;
import org.dash.wallet.common.ui.BaseLockScreenFragment;

import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import de.schildbach.wallet.AppDatabase;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.data.AddressBookProvider;
import de.schildbach.wallet.data.BlockchainIdentityBaseData;
import de.schildbach.wallet.data.BlockchainIdentityData;
import de.schildbach.wallet.data.BlockchainState;
import de.schildbach.wallet.ui.AbstractWalletActivity;
import de.schildbach.wallet.ui.CreateUsernameActivity;
import de.schildbach.wallet.ui.LockScreenActivity;
import de.schildbach.wallet.ui.SearchUserActivity;
import de.schildbach.wallet.ui.TransactionDetailsDialogFragment;
import de.schildbach.wallet.ui.TransactionsAdapter;
import de.schildbach.wallet.ui.TransactionsFilterDialog;
import de.schildbach.wallet.ui.TransactionsFilterSharedViewModel;
import de.schildbach.wallet.ui.WalletTransactionsFragmentViewModel;
import de.schildbach.wallet.ui.dashpay.CreateIdentityService;
import de.schildbach.wallet.ui.invite.InviteHandler;
import de.schildbach.wallet_test.R;
import kotlin.Unit;

/**
 * @author Andreas Schildbach
 */
@AndroidEntryPoint
public class WalletTransactionsFragment extends BaseLockScreenFragment implements
        TransactionsAdapter.OnClickListener, OnSharedPreferenceChangeListener {

    private AbstractWalletActivity activity;

    private WalletApplication application;
    private Configuration config;
    private Wallet wallet;
    private ContentResolver resolver;

    private View loading;
    private RecyclerView recyclerView;
    private TransactionsAdapter adapter;
    private TextView syncingText;
    private TransactionsFilterSharedViewModel transactionsFilterSharedViewModel;
    private MainViewModel mainViewModel;

    private final Handler handler = new Handler();

    private final ContentObserver addressBookObserver = new ContentObserver(handler) {
        @Override
        public void onChange(final boolean selfChange) {
            adapter.clearCacheAndNotifyDataSetChanged();
        }
    };

    private WalletTransactionsFragmentViewModel viewModel;
    @Inject
    public AnalyticsService analytics;

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

        loading = view.findViewById(R.id.loading);

        syncingText = view.findViewById(R.id.syncing);
        view.findViewById(R.id.transaction_filter_btn).setOnClickListener(v -> {
            dialogFragment = new TransactionsFilterDialog();
            dialogFragment.show(getChildFragmentManager(), null);
        });

        mainViewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);

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

        resolver.registerContentObserver(AddressBookProvider.contentUri(activity.getPackageName()), true,
                addressBookObserver);

        config.registerOnSharedPreferenceChangeListener(this);

        updateView();

        mainViewModel = new ViewModelProvider(activity).get(MainViewModel.class);
        viewModel = new ViewModelProvider(this).get(WalletTransactionsFragmentViewModel.class);
        viewModel.getTransactionHistoryItemData().observe(getViewLifecycleOwner(), new Observer<List<TransactionsAdapter.TransactionHistoryItem>>() {
            @Override
            public void onChanged(List<TransactionsAdapter.TransactionHistoryItem> transactions) {
                loading.setVisibility(View.GONE);
                adapter.replace(transactions);
                updateView();
            }
        });
        viewModel.getBlockchainIdentityData().observe(getViewLifecycleOwner(), new Observer<BlockchainIdentityBaseData>() {
            @Override
            public void onChanged(BlockchainIdentityBaseData blockchainIdentityData) {
                if (blockchainIdentityData != null) {
                    ((LockScreenActivity)requireActivity()).imitateUserInteraction();
                    adapter.setBlockchainIdentityData(blockchainIdentityData);
                }
            }
        });
        mainViewModel.isAbleToCreateIdentityLiveData().observe(getViewLifecycleOwner(), canJoinDashPay -> {
            adapter.setCanJoinDashPay(canJoinDashPay);
        });
        AppDatabase.getAppDatabase().blockchainStateDao().load().observe(getViewLifecycleOwner(), new Observer<BlockchainState>() {
            @Override
            public void onChanged(de.schildbach.wallet.data.BlockchainState blockchainState) {
                updateSyncState(blockchainState);
            }
        });
        transactionsFilterSharedViewModel = new ViewModelProvider(requireActivity())
                .get(TransactionsFilterSharedViewModel.class);
        transactionsFilterSharedViewModel.getOnAllTransactionsSelected().observe(getViewLifecycleOwner(), aVoid -> {
            adapter.filter(TransactionsAdapter.Filter.ALL);
        });
        transactionsFilterSharedViewModel.getOnReceivedTransactionsSelected().observe(getViewLifecycleOwner(), aVoid -> {
            adapter.filter(TransactionsAdapter.Filter.INCOMING);
        });
        transactionsFilterSharedViewModel.getOnSentTransactionsSelected().observe(getViewLifecycleOwner(), aVoid -> {
            adapter.filter(TransactionsAdapter.Filter.OUTGOING);
        });
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
    public void onTransactionRowClicked(TransactionsAdapter.TransactionHistoryItem transactionHistoryItem) {
        TransactionDetailsDialogFragment transactionDetailsDialogFragment =
                TransactionDetailsDialogFragment.newInstance(transactionHistoryItem.getTransaction().getTxId());
        requireActivity().getSupportFragmentManager().beginTransaction()
                .add(transactionDetailsDialogFragment, null).commitAllowingStateLoss();
        analytics.logEvent(AnalyticsConstants.Home.TRANSACTION_DETAILS, Bundle.EMPTY);
    }

    @Override
    public void onProcessingIdentityRowClicked(final BlockchainIdentityBaseData blockchainIdentityData, boolean retry) {
        if (retry) {
            // check to see if an invite was used
            if (!blockchainIdentityData.getUsingInvite()) {
                activity.startService(CreateIdentityService.createIntentForRetry(activity, false));
            } else {
                // handle errors from using an invite
                InviteHandler handler = new InviteHandler(activity, analytics);
                if (handler.handleError(blockchainIdentityData)) {
                    adapter.setBlockchainIdentityData(null);
                } else {
                    activity.startService(CreateIdentityService.createIntentForRetryFromInvite(activity, false));
                }
            }
        } else {
            if (blockchainIdentityData.getCreationStateErrorMessage() != null) {
                if (blockchainIdentityData.getCreationState() == BlockchainIdentityData.CreationState.USERNAME_REGISTERING) {
                    startActivity(CreateUsernameActivity.createIntentReuseTransaction(activity, blockchainIdentityData));
                } else {
                    Toast.makeText(getContext(), blockchainIdentityData.getCreationStateErrorMessage(), Toast.LENGTH_LONG).show();
                }
            } else if (blockchainIdentityData.getCreationState() == BlockchainIdentityData.CreationState.DONE) {
                startActivity(new Intent(activity, SearchUserActivity.class));
            }
        }
    }

    @Override
    public void onJoinDashPayClicked() {
        mainViewModel.getShowCreateUsernameEvent().postValue(Unit.INSTANCE);
    }

    @Override
    public void onUsernameCreatedClicked() {
        mainViewModel.dismissUsernameCreatedCard();
    }

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
