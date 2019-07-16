/*
 * Copyright 2013-2015 the original author or authors.
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


import android.content.Context;
import android.content.AsyncTaskLoader;

import org.bitcoinj.core.MasternodeManager;
import org.bitcoinj.core.MasternodeSync;
import org.bitcoinj.core.MasternodeSyncListener;
import org.bitcoinj.core.MasternodeManagerListener;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.utils.Threading;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.RejectedExecutionException;

/**
 * @author Andreas Schildbach
 */
public final class MasternodeSyncLoader extends AsyncTaskLoader<Integer>
{
	//private LocalBroadcastManager broadcastManager;
	private final MasternodeSync masternodeSync;
	private final MasternodeManager masternodeManager;
	private int masternodeSyncStatus;

	private static final Logger log = LoggerFactory.getLogger(MasternodeSyncLoader.class);

	public MasternodeSyncLoader(final Context context, Wallet wallet)
	{
		super(context);

		//this.broadcastManager = LocalBroadcastManager.getInstance(context.getApplicationContext());
		this.masternodeSync = wallet.getContext().masternodeSync;
		this.masternodeSyncStatus = masternodeSync.getSyncStatusInt();
		this.masternodeManager = wallet.getContext().masternodeManager;
	}

	public MasternodeSyncLoader(final Context context, org.bitcoinj.core.Context dashContext)
	{
		super(context);

		//this.broadcastManager = LocalBroadcastManager.getInstance(context.getApplicationContext());
		this.masternodeSync = dashContext.masternodeSync;
		this.masternodeSyncStatus = masternodeSync.getSyncStatusInt();
		this.masternodeManager = dashContext.masternodeManager;
	}

	@Override
	protected void onStartLoading()
	{
		super.onStartLoading();

		masternodeSync.addEventListener(masternodeSyncListener, Threading.SAME_THREAD);
		//masternodeManager.addEventListener(masternodeManagerListener, Threading.SAME_THREAD);
		//broadcastManager.registerReceiver(masternodeSyncReceiver, new IntentFilter(WalletApplication.ACTION_WALLET_REFERENCE_CHANGED));

		safeForceLoad();
	}

	@Override
	protected void onStopLoading()
	{
		//broadcastManager.unregisterReceiver(masternodeSyncReceiver);
		masternodeSync.removeEventListener(masternodeSyncListener);
		//masternodeManager.removeEventListener(masternodeManagerListener);
		//masternodeSyncListener.removeCallbacks();

		super.onStopLoading();
	}

	@Override
	protected void onReset()
	{
		//broadcastManager.unregisterReceiver(masternodeSyncReceiver);
		masternodeSync.removeEventListener(masternodeSyncListener);
		//masternodeSyncListener.removeCallbacks();

		super.onReset();
	}

	@Override
	public Integer loadInBackground()
	{
		masternodeSyncStatus = masternodeSync.getSyncStatusInt();
		return masternodeSyncStatus;
	}

	private final MasternodeSyncListener masternodeSyncListener = new MasternodeSyncListener()
	{
		@Override
		public void onSyncStatusChanged(int newStatus, double progress)
		{
			masternodeSyncStatus = newStatus;
			safeForceLoad();
		}
	};
	private final MasternodeManagerListener masternodeManagerListener = new MasternodeManagerListener()
	{
		@Override
		public void onMasternodeCountChanged(int newCount)
		{
			//masternodeSyncStatus = newStatus;
			safeForceLoad();
		}
	};
/*
	private final BroadcastReceiver masternodeSyncReceiver = new BroadcastReceiver()
	{
		@Override
		public void onReceive(final Context context, final Intent intent)
		{
			safeForceLoad();
		}
	};
*/
	private void safeForceLoad()
	{
		try
		{
			forceLoad();
		}
		catch (final RejectedExecutionException x)
		{
			log.info("rejected execution: " + MasternodeSyncLoader.this.toString());
		}
	}
}
