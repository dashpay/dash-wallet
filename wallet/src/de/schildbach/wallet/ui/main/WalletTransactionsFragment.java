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
import android.graphics.Rect;
import android.os.Bundle;
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
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.data.BlockchainIdentityBaseData;
import de.schildbach.wallet.data.BlockchainIdentityData;
import de.schildbach.wallet.ui.AbstractWalletActivity;
import de.schildbach.wallet.ui.CreateUsernameActivity;
import de.schildbach.wallet.ui.LockScreenActivity;
import de.schildbach.wallet.ui.SearchUserActivity;
import de.schildbach.wallet.ui.WalletTransactionsFragmentViewModel;
import de.schildbach.wallet.ui.dashpay.CreateIdentityService;
import de.schildbach.wallet.ui.invite.InviteHandler;
import org.bitcoinj.core.Sha256Hash;
import org.dash.wallet.common.transactions.TransactionWrapper;

import java.util.ArrayList;

import de.schildbach.wallet.ui.transactions.TransactionDetailsDialogFragment;
import de.schildbach.wallet.ui.transactions.TransactionGroupDetailsFragment;
import de.schildbach.wallet.ui.transactions.TransactionRowView;
import de.schildbach.wallet_test.R;
import kotlin.Unit;
import kotlinx.coroutines.FlowPreview;

/**
 * @author Andreas Schildbach
 */
@FlowPreview
@AndroidEntryPoint
public class WalletTransactionsFragment extends BaseLockScreenFragment {

    private AbstractWalletActivity activity;

    private WalletApplication application;
    private Configuration config;
    private Wallet wallet;
    private ContentResolver resolver;

    private TextView emptyView;
    private View loading;
    private RecyclerView recyclerView;
    private TransactionAdapter adapter;
    private TextView syncingText;
    private MainViewModel viewModel;

    private WalletTransactionsFragmentViewModel walletTransactionsViewModel;
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
    public void onDestroyView() {
        super.onDestroyView();
        recyclerView.setAdapter(null);
    }

    @Override
    public void onViewCreated(@NonNull View view, @androidx.annotation.Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);
        walletTransactionsViewModel = new ViewModelProvider(this).get(WalletTransactionsFragmentViewModel.class);

        emptyView = view.findViewById(R.id.wallet_transactions_empty);
        loading = view.findViewById(R.id.loading);
        syncingText = view.findViewById(R.id.syncing);

        adapter = new TransactionAdapter(viewModel.getBalanceDashFormat(), getResources(), true, (txView, position) -> {
            viewModel.logEvent(AnalyticsConstants.Home.TRANSACTION_DETAILS);

            if (txView.getTxWrapper() != null) {
                TransactionGroupDetailsFragment fragment = new TransactionGroupDetailsFragment(txView.getTxWrapper());
                fragment.show(getParentFragmentManager(), "transaction_group");
            } else {
                TransactionDetailsDialogFragment transactionDetailsDialogFragment =
                        TransactionDetailsDialogFragment.newInstance(txView.getTxId());
                transactionDetailsDialogFragment.show(getParentFragmentManager(), null);
            }

            return Unit.INSTANCE;
        });

        adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onItemRangeInserted(int positionStart, int itemCount) {
                if (positionStart == 0) {
                    recyclerView.scrollToPosition(0);
                }
            }
        });

//        viewModel.getTransactionHistoryItemData().observe(getViewLifecycleOwner(), new Observer<List<TransactionsAdapter.TransactionHistoryItem>>() {
//            @Override
//            public void onChanged(List<TransactionsAdapter.TransactionHistoryItem> transactions) {
//                loading.setVisibility(View.GONE);
//                adapter.replace(transactions);
//                updateView();
//            }
//        }); // TODO: handle differences

        walletTransactionsViewModel.getBlockchainIdentityData().observe(getViewLifecycleOwner(), new Observer<BlockchainIdentityBaseData>() {
            @Override
            public void onChanged(BlockchainIdentityBaseData blockchainIdentityData) {
                if (blockchainIdentityData != null) {
                    ((LockScreenActivity)requireActivity()).imitateUserInteraction();
//                    adapter.setBlockchainIdentityData(blockchainIdentityData); TODO: check
                }
            }
        });

        viewModel.isAbleToCreateIdentityLiveData().observe(getViewLifecycleOwner(), canJoinDashPay -> {
//            adapter.setCanJoinDashPay(canJoinDashPay); TODO
        });

        view.findViewById(R.id.transaction_filter_btn).setOnClickListener(v -> {
            TransactionsFilterDialog dialogFragment = new TransactionsFilterDialog((direction, dialog) -> {
                viewModel.setTransactionsDirection(direction);
                viewModel.logDirectionChangedEvent(direction);
                return Unit.INSTANCE;
            });

            dialogFragment.show(getChildFragmentManager(), null);
        });

        recyclerView = view.findViewById(R.id.wallet_transactions_list);
        recyclerView.setHasFixedSize(true);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);
        recyclerView.addItemDecoration(new RecyclerView.ItemDecoration() {
            private final int VERTICAL = 3 * getResources().getDimensionPixelOffset(R.dimen.card_padding_vertical);
            private final int HORIZONTAL = getResources().getDimensionPixelOffset(R.dimen.default_horizontal_padding);

            @Override
            public void getItemOffsets(@NonNull final Rect outRect, @NonNull final View view, @NonNull final RecyclerView parent,
                                       @NonNull final RecyclerView.State state) {
                super.getItemOffsets(outRect, view, parent, state);

                outRect.left = HORIZONTAL;
                outRect.right = HORIZONTAL;
                outRect.top = VERTICAL;
                outRect.bottom = VERTICAL;
            }
        });

        viewModel.isBlockchainSynced().observe(getViewLifecycleOwner(), isSynced -> updateSyncState());
        viewModel.getBlockchainSyncPercentage().observe(getViewLifecycleOwner(), percentage -> updateSyncState());
        viewModel.getTransactions().observe(getViewLifecycleOwner(), transactionViews -> {
            loading.setVisibility(View.GONE);
            adapter.submitList(transactionViews);

            if (transactionViews.isEmpty()) {
                showEmptyView();
            } else {
                showTransactionList();
            }
        });
    }

    private void updateSyncState() {
        Boolean isSynced = viewModel.isBlockchainSynced().getValue();
        Integer percentage = viewModel.getBlockchainSyncPercentage().getValue();

        if (isSynced != null && isSynced) {
            syncingText.setVisibility(View.GONE);
        } else {
            syncingText.setVisibility(View.VISIBLE);
            String syncing = getString(R.string.syncing);

            if (percentage == null || percentage == 0) {
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
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container,
                             final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.wallet_transactions_fragment, container, false);
    }

//    @Override
//    public void onTransactionRowClicked(TransactionsAdapter.TransactionHistoryItem transactionHistoryItem) {
//        TransactionDetailsDialogFragment transactionDetailsDialogFragment =
//                TransactionDetailsDialogFragment.newInstance(transactionHistoryItem.getTransaction().getTxId());
//        requireActivity().getSupportFragmentManager().beginTransaction()
//                .add(transactionDetailsDialogFragment, null).commitAllowingStateLoss();
//        analytics.logEvent(AnalyticsConstants.Home.TRANSACTION_DETAILS, Bundle.EMPTY);
//    }

//    @Override
//    public void onProcessingIdentityRowClicked(final BlockchainIdentityBaseData blockchainIdentityData, boolean retry) {
//        if (retry) {
//            // check to see if an invite was used
//            if (!blockchainIdentityData.getUsingInvite()) {
//                activity.startService(CreateIdentityService.createIntentForRetry(activity, false));
//            } else {
//                // handle errors from using an invite
//                InviteHandler handler = new InviteHandler(activity, analytics);
//                if (handler.handleError(blockchainIdentityData)) {
//                    adapter.setBlockchainIdentityData(null);
//                } else {
//                    activity.startService(CreateIdentityService.createIntentForRetryFromInvite(activity, false));
//                }
//            }
//        } else {
//            if (blockchainIdentityData.getCreationStateErrorMessage() != null) {
//                if (blockchainIdentityData.getCreationState() == BlockchainIdentityData.CreationState.USERNAME_REGISTERING) {
//                    startActivity(CreateUsernameActivity.createIntentReuseTransaction(activity, blockchainIdentityData));
//                } else {
//                    Toast.makeText(getContext(), blockchainIdentityData.getCreationStateErrorMessage(), Toast.LENGTH_LONG).show();
//                }
//            } else if (blockchainIdentityData.getCreationState() == BlockchainIdentityData.CreationState.DONE) {
//                startActivity(new Intent(activity, SearchUserActivity.class));
//            }
//        }
//    }
//
//    @Override
//    public void onJoinDashPayClicked() {
//        viewModel.getShowCreateUsernameEvent().postValue(Unit.INSTANCE);
//    }
//
//    @Override
//    public void onUsernameCreatedClicked() {
//        viewModel.dismissUsernameCreatedCard();
//    }

    private void showTransactionList() {
        emptyView.setVisibility(View.GONE);
        recyclerView.setVisibility(View.VISIBLE);
    }

    private void showEmptyView() {
        emptyView.setVisibility(View.VISIBLE);
        recyclerView.setVisibility(View.INVISIBLE);
    }

    public boolean isHistoryEmpty() {
        return adapter.getItemCount() == 0;
    }
}
