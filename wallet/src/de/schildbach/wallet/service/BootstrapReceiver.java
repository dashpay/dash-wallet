/*
 * Copyright the original author or authors.
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

package de.schildbach.wallet.service;

import org.bitcoinj.utils.ContextPropagatingThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.dash.wallet.common.Configuration;

import dagger.hilt.android.AndroidEntryPoint;
import de.schildbach.wallet.Constants;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.text.format.DateUtils;

import androidx.annotation.WorkerThread;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.inject.Inject;

/**
 * Handles {@code BOOT_COMPLETED} and the inactivity notification's dismiss actions.
 *
 * <p>This receiver must never start {@link BlockchainServiceImpl}, directly or through a
 * helper. Apps targeting Android 15+ cannot start a {@code dataSync} foreground service from
 * a {@code BOOT_COMPLETED} receiver, and Play reports any path from this class to
 * {@code startForeground}. App updates ({@code MY_PACKAGE_REPLACED}), which must start
 * syncing, are handled by {@link PackageReplacedReceiver}.
 *
 * @author Andreas Schildbach
 */
@SuppressLint("WrongConstant")
@AndroidEntryPoint
public class BootstrapReceiver extends BroadcastReceiver {
    private final Executor executor = Executors.newSingleThreadExecutor(new ContextPropagatingThreadFactory("bootstrap"));

    private static final Logger log = LoggerFactory.getLogger(BootstrapReceiver.class);

    // package-private: InactivityReminder builds the dismiss actions that target this receiver
    static final String ACTION_DISMISS = BootstrapReceiver.class.getPackage().getName() + ".dismiss";
    static final String ACTION_DISMISS_FOREVER = BootstrapReceiver.class.getPackage().getName() +
            ".dismiss_forever";

    @Inject
    protected Configuration config;
    @Inject
    protected InactivityReminder inactivityReminder;

    @Override
    public void onReceive(final Context context, final Intent intent) {
        log.info("got broadcast: {}", intent);
        final PendingResult result = goAsync();
        executor.execute(() -> {
            org.bitcoinj.core.Context.propagate(Constants.CONTEXT);
            onAsyncReceive(context, intent);
            result.finish();
        });
    }

    @WorkerThread
    private void onAsyncReceive(final Context context, final Intent intent) {
        final String action = intent.getAction();

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            // DELIBERATELY DOES NOT START SYNCING, on any Android version.
            //
            // Android 15+ forbids a BOOT_COMPLETED receiver from starting a `dataSync`
            // foreground service (the type BlockchainServiceImpl declares) for apps targeting
            // API 35+, and Play flags any such path in this class. On Android 10-14 the old
            // startBlockchainService(false) call here could not start it either: its
            // process-importance guard requires IMPORTANCE_FOREGROUND, and a process woken for
            // a broadcast sits below that (the same guard made the alarm-driven start a no-op
            // on the 2026-09-17 cold-boot test, §25 of the upgrade memory and sync plan).
            //
            // Product decision 2026-09-17: background catch-up after a reboot is NOT a
            // requirement. Syncing resumes when the user opens the app, which starts the
            // service from the foreground where it is always allowed.
            log.info("post-boot sync not attempted: the blockchain service is not started from "
                    + "BOOT_COMPLETED. Syncing resumes when the app is opened.");

            // if the app hasn't been used for a while and contains coins, maybe show reminder
            inactivityReminder.maybeShow();
        } else if (ACTION_DISMISS.equals(action)) {
            dismissNotification(context);
        } else if (ACTION_DISMISS_FOREVER.equals(action)) {
            dismissNotificationForever(context);
        } else {
            throw new IllegalArgumentException(action);
        }
    }

    @WorkerThread
    private void dismissNotification(final Context context) {
        log.info("dismissing inactivity notification");
        config.setRemindBalanceTimeIn(DateUtils.DAY_IN_MILLIS);
        // through the reminder's lock, so a reminder being prepared cannot post after this
        InactivityReminder.cancel(context);
    }

    @WorkerThread
    private void dismissNotificationForever(final Context context) {
        log.info("dismissing inactivity notification forever");
        config.setRemindBalanceTimeIn(DateUtils.WEEK_IN_MILLIS * 52);
        config.setRemindBalance(false);
        // through the reminder's lock, so a reminder being prepared cannot post after this
        InactivityReminder.cancel(context);
    }

}
