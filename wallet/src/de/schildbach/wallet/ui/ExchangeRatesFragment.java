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

import javax.annotation.Nullable;

import org.bitcoinj.core.Coin;
import org.bitcoinj.utils.ExchangeRate;
import org.bitcoinj.wallet.Wallet;
import org.dash.wallet.common.ui.CurrencyTextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.google.common.base.Strings;

import org.dash.wallet.common.Configuration;

import de.schildbach.wallet.AppDatabase;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.WalletBalanceWidgetProvider;
import de.schildbach.wallet.data.BlockchainState;
import de.schildbach.wallet.rates.ExchangeRatesViewModel;
import de.schildbach.wallet_test.R;

import android.annotation.SuppressLint;
import android.app.Activity;

import androidx.annotation.IntegerRes;
import androidx.annotation.NonNull;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProviders;

import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;

import androidx.fragment.app.Fragment;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;

import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.SearchView.OnQueryTextListener;

import android.widget.ImageView;
import android.widget.TextView;
import android.widget.ViewAnimator;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * @author Andreas Schildbach
 */
public final class ExchangeRatesFragment extends Fragment implements OnSharedPreferenceChangeListener, TextWatcher {
    private AbstractBindServiceActivity activity;
    private WalletApplication application;
    private Configuration config;
    private Wallet wallet;
    private LoaderManager loaderManager;

    private View loading;
    private View loadingErrorView;
    private View emptySearchView;
    private ViewAnimator viewGroup;
    private RecyclerView recyclerView;
    private ExchangeRatesAdapter adapter;
    private ExchangeRatesViewModel exchangeRatesViewModel;
    private EditText searchView;
    private String query = null;

    private static final int ID_BALANCE_LOADER = 0;
    private static final int ID_RATE_LOADER = 1;
    private static final int ID_BLOCKCHAIN_STATE_LOADER = 2;

    @Override
    public void onAttach(final Activity activity) {
        super.onAttach(activity);

        this.activity = (AbstractBindServiceActivity) activity;
        this.application = (WalletApplication) activity.getApplication();
        this.config = application.getConfiguration();
        this.wallet = application.getWallet();
        this.loaderManager = getLoaderManager();
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setRetainInstance(true);
        setHasOptionsMenu(true);

        adapter = new ExchangeRatesAdapter();
        adapter.setRateBase(config.getBtcBase());
        adapter.setDefaultCurrency(config.getExchangeCurrencyCode());
    }

    @Override
    public void onViewCreated(@NonNull View view, @androidx.annotation.Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        exchangeRatesViewModel = ViewModelProviders.of(this)
                .get(ExchangeRatesViewModel.class);
        exchangeRatesViewModel.isLoading().observe(getViewLifecycleOwner(), new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean isLoading) {
                loading.setVisibility(Boolean.TRUE.equals(isLoading) ? View.VISIBLE : View.GONE);
            }
        });
        exchangeRatesViewModel.hasError().observe(getViewLifecycleOwner(), new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean hasError) {
                if (Boolean.TRUE.equals(hasError)) {
                    showOnly(loadingErrorView);
                }
            }
        });
        exchangeRatesViewModel.getRates().observe(getViewLifecycleOwner(),
                new Observer<List<de.schildbach.wallet.rates.ExchangeRate>>() {
                    @Override
                    public void onChanged(List<de.schildbach.wallet.rates.ExchangeRate> exchangeRates) {
                        updateView(exchangeRates);
                    }
                });
        config.registerOnSharedPreferenceChangeListener(this);

        AppDatabase.getAppDatabase().blockchainStateDao().load().observe(getViewLifecycleOwner(),
                new Observer<de.schildbach.wallet.data.BlockchainState>() {
                    @Override
                    public void onChanged(de.schildbach.wallet.data.BlockchainState blockchainState) {
                        adapter.setBlockchainState(blockchainState);
                    }
                });
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container,
            final Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.exchange_rates_fragment, container, false);
        viewGroup = (ViewAnimator) view.findViewById(R.id.exchange_rates_list_group);

        loading = view.findViewById(R.id.exchange_rates_loading);
        emptySearchView = view.findViewById(R.id.exchange_rates_empty_search);
        loadingErrorView = view.findViewById(R.id.exchange_rates_loading_error);
        recyclerView = view.findViewById(R.id.exchange_rates_list);
        recyclerView.setLayoutManager(new LinearLayoutManager(activity));
        recyclerView.setAdapter(adapter);
        searchView = view.findViewById(R.id.search_exchange_rate_btn);
        searchView.addTextChangedListener(this);
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();

        exchangeRatesViewModel.getRates();
        loaderManager.initLoader(ID_BALANCE_LOADER, null, balanceLoaderCallbacks);
    }

    @Override
    public void onPause() {
        loaderManager.destroyLoader(ID_BALANCE_LOADER);
        loaderManager.destroyLoader(ID_BLOCKCHAIN_STATE_LOADER);

        super.onPause();
    }

    @Override
    public void onDestroy() {
        config.unregisterOnSharedPreferenceChangeListener(this);

        //loaderManager.destroyLoader(ID_RATE_LOADER);

        super.onDestroy();
    }

    @Override
    public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences, final String key) {
        if (Configuration.PREFS_KEY_EXCHANGE_CURRENCY.equals(key))
            adapter.setDefaultCurrency(config.getExchangeCurrencyCode());
        else if (Configuration.PREFS_KEY_BTC_PRECISION.equals(key))
            adapter.setRateBase(config.getBtcBase());
    }

    private void showOnly(View view) {
        loading.setVisibility(View.GONE);
        loadingErrorView.setVisibility(View.GONE);
        emptySearchView.setVisibility(View.GONE);
        recyclerView.setVisibility(View.GONE);

        view.setVisibility(View.VISIBLE);
    }

    private void updateView(List<de.schildbach.wallet.rates.ExchangeRate> exchangeRates) {
        adapter.setExchangeRates(exchangeRates);

        if (adapter.getItemCount() == 0 && query == null) {
            showOnly(loadingErrorView);
        } else if (adapter.getItemCount() == 0 && query != null) {
            viewGroup.getHandler().post(new Runnable() {
                @Override
                public void run() {
                    showOnly(emptySearchView);
                }
            });
        } else {
            showOnly(recyclerView);
        }

        final int positionToScrollTo = adapter.getDefaultCurrencyPosition();
        if (positionToScrollTo != RecyclerView.NO_POSITION) {
            recyclerView.scrollToPosition(positionToScrollTo);
        }
    }

    private final LoaderManager.LoaderCallbacks<Coin> balanceLoaderCallbacks = new LoaderManager.LoaderCallbacks<Coin>() {
        @Override
        public Loader<Coin> onCreateLoader(final int id, final Bundle args) {
            return new WalletBalanceLoader(activity, wallet);
        }

        @Override
        public void onLoadFinished(final Loader<Coin> loader, final Coin balance) {
            adapter.setBalance(balance);
        }

        @Override
        public void onLoaderReset(final Loader<Coin> loader) {
        }
    };

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {

    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
        final String input = s.toString();
        query = Strings.emptyToNull(input.trim());
        exchangeRatesViewModel.searchRates(query).observe(ExchangeRatesFragment.this,
                new Observer<List<de.schildbach.wallet.rates.ExchangeRate>>() {
                    @Override
                    public void onChanged(List<de.schildbach.wallet.rates.ExchangeRate> exchangeRates) {
                        updateView(exchangeRates);
                    }
                });
    }

    @Override
    public void afterTextChanged(Editable s) {
        searchView.clearFocus();
    }

    private final class ExchangeRatesAdapter extends RecyclerView.Adapter<ExchangeRateViewHolder> {
        private final LayoutInflater inflater = LayoutInflater.from(activity);

        //private Cursor cursor = null;
        private List<de.schildbach.wallet.rates.ExchangeRate> exchangeRates;
        private Coin rateBase = Coin.COIN;
        @Nullable
        private String defaultCurrency = null;
        @Nullable
        private Coin balance = null;
        @Nullable
        private BlockchainState blockchainState = null;
        @Nullable
        private String defaultCurrencyFlag = null;

        private ExchangeRatesAdapter() {
            setHasStableIds(true);
        }

        public void setDefaultCurrency(final String defaultCurrency) {
            this.defaultCurrency = defaultCurrency;
            notifyDataSetChanged();
        }

        public void setRateBase(final Coin rateBase) {
            this.rateBase = rateBase;
            notifyDataSetChanged();
        }

        public void setBalance(final Coin balance) {
            this.balance = balance;
            notifyDataSetChanged();
        }

        public void setBlockchainState(final BlockchainState blockchainState) {
            //TODO: What's the relation between exchange rates and the blockchain state?
            this.blockchainState = blockchainState;
            notifyDataSetChanged();
        }

        public int getDefaultCurrencyPosition() {
            if (exchangeRates == null || defaultCurrency == null) {
                return RecyclerView.NO_POSITION;
            }

            int i = 0;
            for (de.schildbach.wallet.rates.ExchangeRate rate : exchangeRates) {
                if (rate.getCurrencyCode().equalsIgnoreCase(defaultCurrency)) {
                    return i;
                }
                i++;
            }

            return RecyclerView.NO_POSITION;
        }

        @Override
        public int getItemCount() {
            return exchangeRates != null ? exchangeRates.size() : 0;
        }

        @Override
        public long getItemId(final int position) {
            return exchangeRates.get(position).getCurrencyCode().hashCode();
        }

        @Override
        public ExchangeRateViewHolder onCreateViewHolder(final ViewGroup parent, final int viewType) {
            return new ExchangeRateViewHolder(inflater.inflate(R.layout.exchange_rate_row, parent, false));
        }

        @SuppressLint("SetTextI18n")
        @Override
        public void onBindViewHolder(final ExchangeRateViewHolder holder, final int position) {
            final de.schildbach.wallet.rates.ExchangeRate exchangeRate = exchangeRates.get(position);

            //TODO: Try to remove it from here (?) Maybe cache the creation in exchangeRate?
            ExchangeRate rate = new org.bitcoinj.utils.ExchangeRate(
                    Coin.COIN, exchangeRate.getFiat());

            final boolean isDefaultCurrency = exchangeRate.getCurrencyCode().equals(defaultCurrency);

            holder.defaultCurrencyCheckbox.setOnCheckedChangeListener(null);
            holder.defaultCurrencyCheckbox.setChecked(isDefaultCurrency);

            holder.currencyCode.setText(exchangeRate.getCurrencyCode());
            holder.currencyName.setText(exchangeRate.getCurrencyName(getActivity()));
            holder.price.setFormat(!rateBase.isLessThan(Coin.COIN) ? Constants.LOCAL_FORMAT.minDecimals(2)
                    : Constants.LOCAL_FORMAT.minDecimals(4));
            holder.price.setAmount(rate.coinToFiat(rateBase));

            holder.defaultCurrencyCheckbox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    if (isChecked) {
                        setDefaultCurrency(exchangeRate.getCurrencyCode());
                        config.setExchangeCurrencyCode(exchangeRate.getCurrencyCode());
                        WalletBalanceWidgetProvider.updateWidgets(activity, wallet);
                    }
                }
            });

            Glide.with(getActivity()).load(getFlagFromCurrencyCode(exchangeRate.getCurrencyCode())).apply(RequestOptions.circleCropTransform()).into(holder.currencyFlag);
            holder.itemSeparator.setVisibility(position == getItemCount() - 1 ? View.GONE : View.VISIBLE);
        }

        void setExchangeRates(List<de.schildbach.wallet.rates.ExchangeRate> exchangeRates) {
            this.exchangeRates = exchangeRates;
            notifyDataSetChanged();
        }
    }

    private Integer getFlagFromCurrencyCode(String currencyCode) {
        if (currencyCode.equalsIgnoreCase("try")) return R.drawable.turk;    //we can't use 'try' as the recource name for turkish flag as try is a reserved keyword in java. A workaround is to use rename the turkish flag resource
        else {
            final int resourceId = getResources().getIdentifier(currencyCode.toLowerCase(Locale.ROOT), "drawable", getActivity().getPackageName());
            return resourceId == 0 ? R.drawable.usd : resourceId;
        }
    }

    private final class ExchangeRateViewHolder extends RecyclerView.ViewHolder {

        private final TextView currencyCode;
        private final TextView currencyName;
        private final CurrencyTextView price;
        private final CheckBox defaultCurrencyCheckbox;
        private final ImageView currencyFlag;
        private final View itemSeparator;

        ExchangeRateViewHolder(final View itemView) {
            super(itemView);
            currencyCode = itemView.findViewById(R.id.local_currency_code);
            currencyName = itemView.findViewById(R.id.local_currency_name);
            price = itemView.findViewById(R.id.price);
            defaultCurrencyCheckbox = itemView.findViewById(R.id.checkbox);
            currencyFlag = itemView.findViewById(R.id.local_currency_flag);
            itemSeparator = itemView.findViewById(R.id.item_divider);
        }

    }
}
