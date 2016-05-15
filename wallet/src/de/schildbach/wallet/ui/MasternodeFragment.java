/*
 * Copyright 2012-2015 the original author or authors.
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
import android.app.Fragment;
import android.app.LoaderManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.Loader;
import android.content.ServiceConnection;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.ViewAnimator;

import org.bitcoinj.core.Peer;
import org.bitcoinj.core.VersionMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.service.BlockchainService;
import de.schildbach.wallet.service.BlockchainServiceImpl;
import hashengineering.darkcoin.wallet.R;

/**
 * @author Andreas Schildbach
 */
public final class MasternodeFragment extends Fragment
{
	private AbstractWalletActivity activity;
	private LoaderManager loaderManager;

	private BlockchainService service;

	private TextView liteModeView;
	private TextView masterNodeInfo;
	//private TextView myMasternodeCount;

	private ViewAnimator viewGroup;
	//private RecyclerView recyclerView;
	//private PeerViewAdapter adapter;

	private final Handler handler = new Handler();

	private static final long REFRESH_MS = DateUtils.SECOND_IN_MILLIS;

	private static final int ID_MASTERNODE_LOADER = 0;


	private static final Logger log = LoggerFactory.getLogger(MasternodeFragment.class);

	private org.bitcoinj.core.Context dashContext;

	@Override
	public void onAttach(final Activity activity)
	{
		super.onAttach(activity);

		this.activity = (AbstractWalletActivity) activity;
		this.loaderManager = getLoaderManager();
		this.dashContext = ((WalletApplication) activity.getApplication()).getWallet().getContext();
	}

	@Override
	public void onActivityCreated(final Bundle savedInstanceState)
	{
		super.onActivityCreated(savedInstanceState);

		activity.bindService(new Intent(activity, BlockchainServiceImpl.class), serviceConnection, Context.BIND_AUTO_CREATE);
	}

	@Override
	public void onCreate(final Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);

		//adapter = new PeerViewAdapter();

	}

	@Override
	public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState)
	{
		final View view = inflater.inflate(R.layout.masternode_fragment, container, false);

		//viewGroup = (ViewAnimator) view.findViewById(R.id.peer_list_group);
		liteModeView = (TextView)view.findViewById(R.id.masternode_lite_mode);
		masterNodeInfo = (TextView)view.findViewById(R.id.masternode_info);

		return view;
	}

	@Override
	public void onResume()
	{
		super.onResume();


	}

	@Override
	public void onPause()
	{
		handler.removeCallbacksAndMessages(null);

		super.onPause();
	}

	@Override
	public void onDestroy()
	{
		activity.unbindService(serviceConnection);



		super.onDestroy();
	}

	private final ServiceConnection serviceConnection = new ServiceConnection()
	{
		@Override
		public void onServiceConnected(final ComponentName name, final IBinder binder)
		{
			service = ((BlockchainServiceImpl.LocalBinder) binder).getService();

			loaderManager.initLoader(ID_MASTERNODE_LOADER, null, masternodeLoaderCallbacks);
		}

		@Override
		public void onServiceDisconnected(final ComponentName name)
		{
			loaderManager.destroyLoader(ID_MASTERNODE_LOADER);

			service = null;
		}
	};
	private void updateView()
	{
		int myMasternodes = dashContext.masternodeManager.countEnabled();
		int totalMasternodes = dashContext.masternodeSync.masterNodeCountFromNetwork();
		masterNodeInfo.setText(myMasternodes+ " / " + totalMasternodes );
		liteModeView.setText(dashContext.isLiteMode() ? "ON" : "OFF");
	}



	private final LoaderManager.LoaderCallbacks<Integer> masternodeLoaderCallbacks = new LoaderManager.LoaderCallbacks<Integer>()
	{
		@Override
		public Loader<Integer> onCreateLoader(final int id, final Bundle args)
		{
			return new MasternodeLoader(activity, dashContext);
		}

		@Override
		public void onLoadFinished(final Loader<Integer> loader, final Integer newStatus)
		{
			//WalletBalanceToolbarFragment.this.masternodeSyncStatus = newStatus;

			updateView();

		}

		@Override
		public void onLoaderReset(final Loader<Integer> loader)
		{
		}
	};
}
