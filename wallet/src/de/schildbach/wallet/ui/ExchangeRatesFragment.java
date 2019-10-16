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

import com.google.common.base.Strings;

import org.dash.wallet.common.Configuration;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.WalletBalanceWidgetProvider;
import de.schildbach.wallet.data.WalletLock;
import de.schildbach.wallet.rates.ExchangeRatesViewModel;
import de.schildbach.wallet.service.BlockchainState;
import de.schildbach.wallet.service.BlockchainStateLoader;
import de.schildbach.wallet_test.R;
import android.app.Activity;
import android.content.Context;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProviders;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.graphics.Color;
import android.os.Bundle;
import androidx.fragment.app.Fragment;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.SearchView.OnQueryTextListener;
import android.widget.TextView;
import android.widget.ViewAnimator;

import java.util.List;

/**
 * @author Andreas Schildbach
 */
public final class ExchangeRatesFragment extends Fragment implements OnSharedPreferenceChangeListener {
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

    private String query = null;
    @Nullable
    private BlockchainState blockchainState = null;

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

        exchangeRatesViewModel = ViewModelProviders.of(this)
                .get(ExchangeRatesViewModel.class);
        exchangeRatesViewModel.isLoading().observe(this, new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean isLoading) {
                loading.setVisibility(Boolean.TRUE.equals(isLoading) ? View.VISIBLE : View.GONE);
            }
        });
        exchangeRatesViewModel.hasError().observe(this, new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean hasError) {
                if (Boolean.TRUE.equals(hasError)) {
                    showOnly(loadingErrorView);
                }
            }
        });
        exchangeRatesViewModel.getRates().observe(this,
                new Observer<List<de.schildbach.wallet.rates.ExchangeRate>>() {
                    @Override
                    public void onChanged(List<de.schildbach.wallet.rates.ExchangeRate> exchangeRates) {
                        updateView(exchangeRates);
                    }
                });
        config.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container,
            final Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.exchange_rates_fragment, container, false);
        viewGroup = (ViewAnimator) view.findViewById(R.id.exchange_rates_list_group);

        loading = view.findViewById(R.id.exchange_rates_loading);
        emptySearchView = view.findViewById(R.id.exchange_rates_empty_search);
        loadingErrorView = view.findViewById(R.id.exchange_rates_loading_error);

        recyclerView = (RecyclerView) view.findViewById(R.id.exchange_rates_list);
        recyclerView.setLayoutManager(new LinearLayoutManager(activity));
        recyclerView.setAdapter(adapter);
        recyclerView.addItemDecoration(new DividerItemDecoration(getActivity(), DividerItemDecoration.VERTICAL_LIST));
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();

        exchangeRatesViewModel.getRates();
        loaderManager.initLoader(ID_BALANCE_LOADER, null, balanceLoaderCallbacks);
        loaderManager.initLoader(ID_BLOCKCHAIN_STATE_LOADER, null, blockchainStateLoaderCallbacks);
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
    public void onCreateOptionsMenu(final Menu menu, final MenuInflater inflater) {
        inflater.inflate(R.menu.exchange_rates_fragment_options, menu);

        MenuItem walletLockMenuItem = menu.findItem(R.id.wallet_options_lock);
        walletLockMenuItem.setVisible(WalletLock.getInstance().isWalletLocked(wallet));

        final SearchView searchView = (SearchView) menu.findItem(R.id.exchange_rates_options_search).getActionView();
        searchView.setOnQueryTextListener(new OnQueryTextListener() {
            @Override
            public boolean onQueryTextChange(final String newText) {
                query = Strings.emptyToNull(newText.trim());
                exchangeRatesViewModel.searchRates(query).observe(ExchangeRatesFragment.this,
                        new Observer<List<de.schildbach.wallet.rates.ExchangeRate>>() {
                    @Override
                    public void onChanged(List<de.schildbach.wallet.rates.ExchangeRate> exchangeRates) {
                        updateView(exchangeRates);
                    }
                });
                return true;
            }

            @Override
            public boolean onQueryTextSubmit(final String query) {
                searchView.clearFocus();

                return true;
            }
        });

        // Workaround for not being able to style the SearchView
        final int id = searchView.getContext().getResources().getIdentifier("android:id/search_src_text", null, null);
        final View searchInput = searchView.findViewById(id);
        if (searchInput instanceof EditText)
            ((EditText) searchInput).setTextColor(Color.WHITE);

        super.onCreateOptionsMenu(menu, inflater);
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

    private final LoaderManager.LoaderCallbacks<BlockchainState> blockchainStateLoaderCallbacks = new LoaderManager.LoaderCallbacks<BlockchainState>() {
        @Override
        public Loader<BlockchainState> onCreateLoader(final int id, final Bundle args) {
            return new BlockchainStateLoader(activity);
        }

        @Override
        public void onLoadFinished(final Loader<BlockchainState> loader, final BlockchainState blockchainState) {
            adapter.setBlockchainState(blockchainState);
        }

        @Override
        public void onLoaderReset(final Loader<BlockchainState> loader) {
        }
    };

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

        @Override
        public void onBindViewHolder(final ExchangeRateViewHolder holder, final int position) {
            final de.schildbach.wallet.rates.ExchangeRate exchangeRate = exchangeRates.get(position);

            //TODO: Try to remove it from here (?) Maybe cache the creation in exchangeRate?
            ExchangeRate rate = new org.bitcoinj.utils.ExchangeRate(
                    Coin.COIN, exchangeRate.getFiat());

            final boolean isDefaultCurrency = exchangeRate.getCurrencyCode().equals(defaultCurrency);

            holder.itemView.setBackgroundResource(isDefaultCurrency ? R.color.bg_list_selected : R.color.bg_list);

            int colorPrimary = getActivity().getResources().getColor(R.color.colorPrimary);
            int colorDarkestBlue = getActivity().getResources().getColor(R.color.darkest_blue);

            holder.currencyCodeView.setTextColor(isDefaultCurrency ? colorPrimary : colorDarkestBlue);
            holder.rateLabel.setTextColor(isDefaultCurrency ? colorPrimary : colorDarkestBlue);
            holder.rateView.setTextColor(isDefaultCurrency ? colorPrimary : colorDarkestBlue);

            holder.currencyCodeView.setText(exchangeRate.getCurrencyCode());

            holder.rateView.setFormat(!rateBase.isLessThan(Coin.COIN) ? Constants.LOCAL_FORMAT.minDecimals(2)
                    : Constants.LOCAL_FORMAT.minDecimals(4));
            holder.rateView.setAmount(rate.coinToFiat(rateBase));

            holder.walletView.setFormat(Constants.LOCAL_FORMAT);
            if (balance != null && (blockchainState == null || !blockchainState.replaying)) {
                holder.walletView.setAmount(rate.coinToFiat(balance));
                holder.walletView.setStrikeThru(!Constants.IS_PROD_BUILD);
            } else {
                holder.walletView.setText("n/a");
                holder.walletView.setStrikeThru(false);
            }

            holder.menuView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(final View v) {
                    Context wrapper = new ContextThemeWrapper(activity, R.style.My_PopupOverlay);
                    final PopupMenu popupMenu = new PopupMenu(wrapper, v);
                    popupMenu.inflate(R.menu.exchange_rates_context);
                    popupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                        @Override
                        public boolean onMenuItemClick(final MenuItem item) {
                            if (item.getItemId() == R.id.exchange_rates_context_set_as_default) {
                                setDefaultCurrency(exchangeRate.getCurrencyCode());
                                config.setExchangeCurrencyCode(exchangeRate.getCurrencyCode());
                                WalletBalanceWidgetProvider.updateWidgets(activity, wallet);
                                return true;
                            } else {
                                return false;
                            }
                        }
                    });
                    popupMenu.show();
                }
            });
        }

        public void setExchangeRates(List<de.schildbach.wallet.rates.ExchangeRate> exchangeRates) {
            this.exchangeRates = exchangeRates;
            notifyDataSetChanged();
        }
    }

    private final class ExchangeRateViewHolder extends RecyclerView.ViewHolder {
        private final TextView currencyCodeView;
        private final TextView rateLabel;
        private final CurrencyTextView rateView;
        private final CurrencyTextView walletView;
        private final ImageButton menuView;

        public ExchangeRateViewHolder(final View itemView) {
            super(itemView);
            currencyCodeView = (TextView) itemView.findViewById(R.id.exchange_rate_row_currency_code);
            rateLabel = (TextView) itemView.findViewById(R.id.exchange_rate_label);
            rateView = (CurrencyTextView) itemView.findViewById(R.id.exchange_rate_row_rate);
            walletView = (CurrencyTextView) itemView.findViewById(R.id.exchange_rate_row_balance);
            menuView = (ImageButton) itemView.findViewById(R.id.exchange_rate_row_menu);
        }
    }
}
