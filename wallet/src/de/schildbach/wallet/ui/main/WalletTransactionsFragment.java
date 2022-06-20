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

import android.graphics.Rect;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.bitcoinj.core.Sha256Hash;
import org.dash.wallet.common.services.analytics.AnalyticsConstants;
import org.dash.wallet.common.transactions.TransactionWrapper;

import java.util.ArrayList;
import java.util.List;

import de.schildbach.wallet.Constants;
import de.schildbach.wallet.ui.transactions.TransactionDetailsDialogFragment;
import de.schildbach.wallet.ui.transactions.TransactionGroupDetailsFragment;
import de.schildbach.wallet.ui.transactions.TransactionRowView;
import de.schildbach.wallet_test.R;
import kotlin.Unit;
import kotlinx.coroutines.ExperimentalCoroutinesApi;
import kotlinx.coroutines.FlowPreview;

/**
 * @author Andreas Schildbach
 */
@FlowPreview
@ExperimentalCoroutinesApi
public class WalletTransactionsFragment extends Fragment {

    private TextView emptyView;
    private View loading;
    private RecyclerView recyclerView;
    private TransactionAdapter adapter;
    private TextView syncingText;
    private MainViewModel viewModel;

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        recyclerView.setAdapter(null);
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container,
                             final Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.wallet_transactions_fragment, container, false);
        viewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);

        emptyView = view.findViewById(R.id.wallet_transactions_empty);
        loading = view.findViewById(R.id.loading);
        syncingText = view.findViewById(R.id.syncing);

        adapter = new TransactionAdapter(viewModel.getBalanceDashFormat(), getResources(), true, (txView, position) -> {
            viewModel.logEvent(AnalyticsConstants.Home.TRANSACTION_DETAILS);
            List<TransactionWrapper> wrappers = viewModel.getTransactions().getValue();

            if (wrappers != null) {
                TransactionWrapper wrapper = wrappers.get(position);

                if (wrapper.getTransactions().size() > 1) {
                    TransactionGroupDetailsFragment fragment = new TransactionGroupDetailsFragment(wrapper);
                    fragment.show(getParentFragmentManager(), "transaction_group");
                } else {
                    Sha256Hash txId = wrapper.getTransactions().iterator().next().getTxId();
                    TransactionDetailsDialogFragment transactionDetailsDialogFragment =
                            TransactionDetailsDialogFragment.newInstance(txId);
                    transactionDetailsDialogFragment.show(getParentFragmentManager(), null);
                }
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
        viewModel.getTransactions().observe(getViewLifecycleOwner(), wrappedTransactions -> {
            loading.setVisibility(View.GONE);
            List<TransactionRowView> transactionViews = new ArrayList<>();

            for (TransactionWrapper wrapper: wrappedTransactions) {
                transactionViews.add(TransactionRowView.Companion.fromTransactionWrapper(
                        wrapper, viewModel.getWalletData().getTransactionBag(),
                        Constants.CONTEXT
                ));
            }

            adapter.submitList(transactionViews);

            if (wrappedTransactions.isEmpty()) {
                showEmptyView();
            } else {
                showTransactionList();
            }
        });

        return view;
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
