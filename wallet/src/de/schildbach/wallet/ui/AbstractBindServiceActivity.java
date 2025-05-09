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

import javax.annotation.Nullable;

import de.schildbach.wallet.service.BlockchainService;
import de.schildbach.wallet.service.BlockchainServiceImpl;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Andreas Schildbach
 */
public abstract class AbstractBindServiceActivity extends LockScreenActivity {

    private static final Logger log = LoggerFactory.getLogger(AbstractBindServiceActivity.class);

    @Nullable
    private BlockchainService blockchainService;

    private boolean shouldUnbind;

    private final ServiceConnection serviceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(final ComponentName name, final IBinder binder) {
            blockchainService = ((BlockchainServiceImpl.LocalBinder) binder).getService();
        }

        @Override
        public void onServiceDisconnected(final ComponentName name) {
            blockchainService = null;
        }
    };

    @Override
    protected void onResume() {
        super.onResume();

        doBindService();
    }

    private void doBindService() {
        if (bindService(new Intent(this, BlockchainServiceImpl.class), serviceConnection, Context.BIND_AUTO_CREATE)) {
            shouldUnbind = true;
        } else {
            log.error("error: the requested service doesn't exist, or this client isn't allowed access to it.");
        }
    }

    @Override
    protected void onPause() {
        doUnbindService();

        super.onPause();
    }

    public void doUnbindService() {
        if (shouldUnbind) {
            unbindService(serviceConnection);
            shouldUnbind = false;
        }
    }

    protected void unbindServiceServiceConnection(){
        doUnbindService();
    }

    @Nullable
    public BlockchainService getBlockchainService() {
        return blockchainService;
    }
}
