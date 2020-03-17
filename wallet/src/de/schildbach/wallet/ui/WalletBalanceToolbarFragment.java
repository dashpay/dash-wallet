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
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProviders;
import android.content.Intent;
import android.os.Bundle;

import androidx.fragment.app.Fragment;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.MasternodeSync;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.utils.Fiat;
import org.dash.wallet.common.ui.CurrencyTextView;

import javax.annotation.Nullable;

import org.dash.wallet.common.Configuration;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.rates.ExchangeRate;
import de.schildbach.wallet.rates.ExchangeRatesViewModel;
import de.schildbach.wallet.util.BlockchainStateUtils;
import de.schildbach.wallet_test.R;

/**
 * @author Andreas Schildbach
 */
public final class WalletBalanceToolbarFragment extends Fragment {
	private WalletApplication application;
	private AbstractBindServiceActivity activity;
	private Configuration config;
	private Wallet wallet;
	private LoaderManager loaderManager;

	private View viewBalance;
	private View progressView;
	private CurrencyTextView viewBalanceBtc;
	private View viewBalanceTooMuch;
	private CurrencyTextView viewBalanceLocal;
	private View appBarBottom;
	private TextView appBarMessageView;

	private boolean showLocalBalance;

	private String progressMessage;

	private ExchangeRatesViewModel exchangeRatesViewModel;

	@Nullable
	private Coin balance = null;
	@Nullable
	private ExchangeRate exchangeRate = null;
	@Nullable
	private int masternodeSyncStatus = MasternodeSync.MASTERNODE_SYNC_FINISHED;

	private static final int ID_BALANCE_LOADER = 0;
	private static final int ID_MASTERNODE_SYNC_LOADER = 2;

	private static final long BLOCKCHAIN_UPTODATE_THRESHOLD_MS = DateUtils.HOUR_IN_MILLIS;
	private static final Coin TOO_MUCH_BALANCE_THRESHOLD = Coin.COIN.multiply(30);

	private boolean initComplete = false;

	@Override
	public void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		setHasOptionsMenu(true);
	}

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        MenuItem walletLockMenuItem = menu.findItem(R.id.wallet_options_lock);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
	public void onAttach(final Activity activity)
	{
		super.onAttach(activity);

		this.activity = (AbstractBindServiceActivity)activity;
		this.application = (WalletApplication) activity.getApplication();
		this.config = application.getConfiguration();
		this.wallet = application.getWallet();
		this.loaderManager = getLoaderManager();

		showLocalBalance = getResources().getBoolean(R.bool.show_local_balance);
	}

	@Override
	public void onActivityCreated(@androidx.annotation.Nullable Bundle savedInstanceState)
	{
		super.onActivityCreated(savedInstanceState);
		appBarMessageView = activity.findViewById(R.id.toolbar_message);
		appBarBottom = activity.findViewById(R.id.toolbar_bottom);
		exchangeRatesViewModel = ViewModelProviders.of(this).get(ExchangeRatesViewModel.class);
	}

	@Override
	public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState)
	{
		return inflater.inflate(R.layout.wallet_balance_toolbar_fragment, container, false);
	}

	@Override
	public void onViewCreated(final View view, final Bundle savedInstanceState)
	{
		super.onViewCreated(view, savedInstanceState);

		progressView = view.findViewById(R.id.progress);

		viewBalance = view.findViewById(R.id.wallet_balance);

		viewBalanceBtc = (CurrencyTextView) view.findViewById(R.id.wallet_balance_btc);
		viewBalanceBtc.setPrefixScaleX(0.9f);

		viewBalanceTooMuch = view.findViewById(R.id.wallet_balance_too_much_warning);

		viewBalanceLocal = (CurrencyTextView) view.findViewById(R.id.wallet_balance_local);
		viewBalanceLocal.setInsignificantRelativeSize(1);
		viewBalanceLocal.setStrikeThru(!Constants.IS_PROD_BUILD);

		viewBalance.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				showWarningIfBalanceTooMuch();
				if(!(getActivity() instanceof ExchangeRatesActivity))
					showExchangeRatesActivity();
			}
		});


	}

	@Override
	public void onResume() {
		super.onResume();

		loaderManager.initLoader(ID_BALANCE_LOADER, null, balanceLoaderCallbacks);

		exchangeRatesViewModel.getRate(config.getExchangeCurrencyCode()).observe(this,
				new Observer<de.schildbach.wallet.rates.ExchangeRate>() {
			@Override
			public void onChanged(de.schildbach.wallet.rates.ExchangeRate rate) {
				if (rate != null) {
					exchangeRate = rate;
					updateView();
				}
			}
		});

		updateView();
	}

	@Override
	public void onPause()
	{
		loaderManager.destroyLoader(ID_BALANCE_LOADER);
		//loaderManager.destroyLoader(ID_MASTERNODE_SYNC_LOADER);

		super.onPause();
	}

	private void updateView()
	{
		if (!isAdded())
			return;

		//TODO: this class will be removed in a future PR, it's not being used anymore.
		final boolean showProgress = false;

		if (!showProgress)
		{
			viewBalance.setVisibility(View.VISIBLE);

			if (!showLocalBalance)
				viewBalanceLocal.setVisibility(View.GONE);

			if (balance != null)
			{
				viewBalanceBtc.setVisibility(View.VISIBLE);
				viewBalanceBtc.setFormat(config.getFormat().noCode());
				viewBalanceBtc.setAmount(balance);

				updateBalanceTooMuchWarning();

				if (showLocalBalance)
				{
					if (exchangeRate != null)
					{
						org.bitcoinj.utils.ExchangeRate rate = new org.bitcoinj.utils.ExchangeRate(Coin.COIN,
								exchangeRate.getFiat());
						final Fiat localValue = rate.coinToFiat(balance);
						viewBalanceLocal.setVisibility(View.VISIBLE);
						viewBalanceLocal.setFormat(Constants.LOCAL_FORMAT.code(0,
								org.dash.wallet.common.Constants.PREFIX_ALMOST_EQUAL_TO + exchangeRate.getCurrencyCode()));
						viewBalanceLocal.setAmount(localValue);
					}
					else
					{
						viewBalanceLocal.setVisibility(View.INVISIBLE);
					}
				}
			}
			else
			{
				viewBalanceBtc.setVisibility(View.INVISIBLE);
			}

			//if(masternodeSyncStatus != MasternodeSync.MASTERNODE_SYNC_FINISHED)
			//{
//                progressView.setVisibility(View.VISIBLE);
			viewBalance.setVisibility(View.VISIBLE);
			//            String syncStatus = wallet.getContext().masternodeSync.getSyncStatus();
			//          showAppBarMessage(syncStatus);
			//    } else {
			//Show sync status of Masternodes
			//int masternodesLoaded = wallet.getContext().masternodeSync.mapSeenSyncMNB.size();
			//int totalMasternodes = wallet.getContext().masternodeSync.masterNodeCountFromNetwork();

			//if(totalMasternodes == 0 || totalMasternodes < masternodesLoaded + 100) {
			progressView.setVisibility(View.GONE);
			showAppBarMessage(null);
			//}
			//else
			//{
			//showAppBarMessage("Masternodes Loaded: " + masternodesLoaded *100 /totalMasternodes +"%");
			//	showAppBarMessage("Masternodes Loaded: " + masternodesLoaded +" of "+ totalMasternodes);
			//}
			//}
			activity.invalidateOptionsMenu();
		}
		else
		{
			showAppBarMessage(progressMessage);
			progressView.setVisibility(View.VISIBLE);
			progressView.setOnClickListener(new OnClickListener()
			{
				@Override
				public void onClick(View v)
				{
					Toast.makeText(application, progressMessage, Toast.LENGTH_LONG).show();
				}
			});
			viewBalance.setVisibility(View.INVISIBLE);
		}
	}

	private void showAppBarMessage(CharSequence message) {
		if (message != null) {
			appBarBottom.setVisibility(View.VISIBLE);
			appBarMessageView.setText(message);
		} else {
			appBarBottom.setVisibility(View.GONE);
		}
	}

	private void updateBalanceTooMuchWarning() {
		if (balance == null)
			return;

		boolean tooMuch = balance.isGreaterThan(TOO_MUCH_BALANCE_THRESHOLD);
		viewBalanceTooMuch.setVisibility(tooMuch ? View.VISIBLE : View.GONE);
	}

	private void showWarningIfBalanceTooMuch() {
		if (balance != null && balance.isGreaterThan(TOO_MUCH_BALANCE_THRESHOLD)) {
			Toast.makeText(application, getString(R.string.wallet_balance_fragment_too_much),
					Toast.LENGTH_LONG).show();
		}
	}

	private void showExchangeRatesActivity() {
		Intent intent = new Intent(getActivity(), ExchangeRatesActivity.class);
		getActivity().startActivity(intent);
	}

	private final LoaderManager.LoaderCallbacks<Coin> balanceLoaderCallbacks = new LoaderManager.LoaderCallbacks<Coin>()
	{
		@Override
		public Loader<Coin> onCreateLoader(final int id, final Bundle args)
		{
			return new WalletBalanceLoader(activity, wallet);
		}

		@Override
		public void onLoadFinished(final Loader<Coin> loader, final Coin balance)
		{
			WalletBalanceToolbarFragment.this.balance = balance;

			updateView();
		}

		@Override
		public void onLoaderReset(final Loader<Coin> loader)
		{
		}
	};
}
