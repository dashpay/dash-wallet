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

package de.schildbach.wallet.ui.rates;

import static android.view.View.VISIBLE;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.Group;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProviders;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.common.base.Strings;

import org.bitcoinj.wallet.Wallet;
import org.dash.wallet.common.Configuration;
import org.dash.wallet.common.data.entity.ExchangeRate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import dagger.hilt.android.AndroidEntryPoint;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.adapter.BaseFilterAdapter;
import de.schildbach.wallet.adapter.ExchangeRatesAdapter;
import de.schildbach.wallet.ui.AbstractBindServiceActivity;
import de.schildbach.wallet_test.R;

/**
 * @author Andreas Schildbach
 */
@AndroidEntryPoint
public final class ExchangeRatesFragment extends DialogFragment implements OnSharedPreferenceChangeListener, TextWatcher, View.OnClickListener, BaseFilterAdapter.ResetViewListener, ExchangeRatesAdapter.onExchangeRateItemSelectedListener {
    private AbstractBindServiceActivity activity;
    private WalletApplication application;
    private Configuration config;
    private Wallet wallet;
    private View loading;
    private View loadingErrorView;
    private View emptySearchView;
    private RecyclerView recyclerView;
    private ExchangeRatesAdapter adapter;
    private ExchangeRatesViewModel exchangeRatesViewModel;
    private EditText searchView;
    private String query = null;
    private TextView closeSearchView;
    private ImageView closeIconView, backPressView;
    private ConstraintLayout viewContainer;
    private Group settingsGroup, sendPaymentGroup;
    public static final String ARG_SHOW_AS_DIALOG = "ARG_SHOW_AS_DIALOG";
    public static final String ARG_CURRENCY_CODE = "ARG_CURRENCY_CODE";
    private boolean showAsDialog;
    public static final String BUNDLE_EXCHANGE_RATE = "BUNDLE_EXCHANGE_RATE";
    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        this.activity = (AbstractBindServiceActivity) context;
        this.application = (WalletApplication) activity.getApplication();
        this.config = application.getConfiguration();
        this.wallet = application.getWallet();
    }

    public static ExchangeRatesFragment newInstance(boolean showAsDialog, String selectedCurrency) {
        ExchangeRatesFragment fragment = new ExchangeRatesFragment();
        Bundle args = new Bundle();
        args.putBoolean(ARG_SHOW_AS_DIALOG, showAsDialog);
        args.putString(ARG_CURRENCY_CODE, selectedCurrency);
        fragment.setArguments(args);
        return fragment;
    }

    public static ExchangeRatesFragment newInstance(String selectedCurrency) {
        return newInstance(true, selectedCurrency);
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showAsDialog = requireActivity().getIntent().getBooleanExtra(ARG_SHOW_AS_DIALOG, false);
        adapter = new ExchangeRatesAdapter(activity, config, wallet, new ArrayList<>(), this, this,showAsDialog);
        adapter.setRateBase(config.getBtcBase());
        String currencyCode = requireActivity().getIntent().getStringExtra(ARG_CURRENCY_CODE);

        if (Strings.isNullOrEmpty(currencyCode)) {
            currencyCode = config.getExchangeCurrencyCode();
        }

        adapter.setDefaultCurrency(currencyCode);
    }

    @Override
    public void onViewCreated(@NonNull View view, @androidx.annotation.Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (showAsDialog){
            setViewPaddingAndBackground();
            sendPaymentGroup.setVisibility(VISIBLE);
        } else {
            settingsGroup.setVisibility(VISIBLE);
        }

        exchangeRatesViewModel = ViewModelProviders.of(this)
                .get(ExchangeRatesViewModel.class);
        exchangeRatesViewModel.isLoading().observe(getViewLifecycleOwner(), new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean isLoading) {
                loading.setVisibility(Boolean.TRUE.equals(isLoading) ? VISIBLE : View.GONE);
            }
        });
        exchangeRatesViewModel.getHasError().observe(getViewLifecycleOwner(), new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean hasError) {
                if (Boolean.TRUE.equals(hasError)) {
                    showOnly(loadingErrorView);
                }
            }
        });
        exchangeRatesViewModel.getRates().observe(getViewLifecycleOwner(),
                new Observer<List<ExchangeRate>>() {
                    @Override
                    public void onChanged(List<ExchangeRate> exchangeRates) {
                        adapter.setItems(exchangeRates);
                        updateView(exchangeRates);
                    }
                });
        config.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container,
                             final Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.exchange_rates_fragment, container, false);
        viewContainer = view.findViewById(R.id.view_container);
        loading = view.findViewById(R.id.exchange_rates_loading);
        emptySearchView = view.findViewById(R.id.exchange_rates_empty_search);
        loadingErrorView = view.findViewById(R.id.exchange_rates_loading_error);
        recyclerView = view.findViewById(R.id.exchange_rates_list);
        searchView = view.findViewById(R.id.search_exchange_rate_btn);
        settingsGroup = view.findViewById(R.id.settings_header_group);
        sendPaymentGroup = view.findViewById(R.id.send_payment_header_group);
        closeSearchView = view.findViewById(R.id.search_close_btn);
        closeIconView = view.findViewById(R.id.close_icon);
        backPressView = view.findViewById(R.id.white_back_press_btn);
        recyclerView.setLayoutManager(new LinearLayoutManager(activity));
        recyclerView.setHasFixedSize(true);
        recyclerView.setAdapter(adapter);
        searchView.addTextChangedListener(this);
        closeSearchView.setOnClickListener(this);
        closeIconView.setOnClickListener(this);
        backPressView.setOnClickListener(this);
        updateCloseButton();
        return view;
    }

    private void updateCloseButton() {
        final boolean hasText = !TextUtils.isEmpty(searchView.getText());
        // Should we show the close button? It is not shown if there's no focus,
        // field is not iconified by default and there is no text in it.
        closeSearchView.setVisibility(hasText ? View.VISIBLE : View.GONE);
        closeIconView.setVisibility(hasText ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onResume() {
        super.onResume();
        exchangeRatesViewModel.getRates();
    }

    @Override
    public void onDestroy() {
        config.unregisterOnSharedPreferenceChangeListener(this);
        super.onDestroy();
    }

    @Override
    public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences, final String key) {
        if (Configuration.PREFS_KEY_EXCHANGE_CURRENCY.equals(key)) {
            adapter.setDefaultCurrency(config.getExchangeCurrencyCode());
        }
    }

    private void showOnly(View view) {
        loading.setVisibility(View.GONE);
        loadingErrorView.setVisibility(View.GONE);
        emptySearchView.setVisibility(View.GONE);
        recyclerView.setVisibility(View.GONE);

        view.setVisibility(VISIBLE);
    }

    private void updateView(List<ExchangeRate> exchangeRates) {
        Collections.sort(exchangeRates, new ExchangeRateComparator());
        adapter.notifyDataSetChanged();

        if (adapter.getItemCount() == 0) {
            showOnly(loadingErrorView);
        } else {
            showOnly(recyclerView);
        }

        final int positionToScrollTo = adapter.getDefaultCurrencyPosition();
        if (positionToScrollTo != RecyclerView.NO_POSITION) {
            recyclerView.scrollToPosition(positionToScrollTo);
        }
    }

    @Override
    public void setViewState() {
        if (adapter.getItemCount() == 0 && query == null) {
            showOnly(loadingErrorView);
        } else if (adapter.getItemCount() == 0 && query != null) {
            showOnly(emptySearchView);
        } else {
            showOnly(recyclerView);
        }
    }

    public class ExchangeRateComparator implements Comparator<ExchangeRate> {

        @Override
        public int compare(ExchangeRate o1, ExchangeRate o2) {
            return o1.getCurrencyName(getActivity()).compareToIgnoreCase(o2.getCurrencyName(getActivity()));
        }
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
        query = Strings.emptyToNull(s.toString().trim());
        updateCloseButton();
        adapter.getFilter().filter(s);
    }

    @Override
    public void afterTextChanged(Editable s) { }

    @Override
    public void onClick(View v) {
        final InputMethodManager imm = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);

        switch(v.getId()) {
            case R.id.close_icon:
                searchView.setText("");
                searchView.requestFocus();

                if (imm.isActive()) {
                    imm.showSoftInput(v, 0);
                }

                break;
            case R.id.search_close_btn:
                searchView.setText("");
                searchView.clearFocus();
                if (imm.isActive()) {
                    imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                }
                break;
            case R.id.white_back_press_btn:
                getActivity().finish();
                break;
            default: break;
        }
    }

    private void setViewPaddingAndBackground() {
        int paddingTopDp = 16;
        float scale = getResources().getDisplayMetrics().density;
        int sizeInDp = (int)(paddingTopDp * scale);
        viewContainer.setPadding(0,sizeInDp,0,0);
    }

    @Override
    public void onItemChecked(ExchangeRate selectedRate) {
        Intent intent = new Intent();
        intent.putExtra(BUNDLE_EXCHANGE_RATE, selectedRate);
        getActivity().setResult(Activity.RESULT_OK, intent);
        getActivity().finish();
    }
}
