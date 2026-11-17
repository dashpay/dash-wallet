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

import org.bitcoinj.core.Coin;
import org.bitcoinj.utils.ContextPropagatingThreadFactory;
import org.dash.wallet.common.money.MonetaryFormat;
import org.bitcoinj.wallet.Wallet;
import de.schildbach.wallet.data.WalletData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.dash.wallet.common.Configuration;

import dagger.hilt.android.AndroidEntryPoint;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.ui.OnboardingActivity;
import de.schildbach.wallet_test.R;

import android.annotation.SuppressLint;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.text.format.DateUtils;

import androidx.annotation.WorkerThread;
import androidx.core.app.NotificationCompat;

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

    private static final String ACTION_DISMISS = BootstrapReceiver.class.getPackage().getName() + ".dismiss";
    private static final String ACTION_DISMISS_FOREVER = BootstrapReceiver.class.getPackage().getName() +
            ".dismiss_forever";

    @Inject
    protected Configuration config;
    @Inject
    protected WalletData walletDataProvider;
    @Inject
    protected WalletApplication application;

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
            maybeShowInactivityNotification(application, config, walletDataProvider);
        } else if (ACTION_DISMISS.equals(action)) {
            dismissNotification(context);
        } else if (ACTION_DISMISS_FOREVER.equals(action)) {
            dismissNotificationForever(context);
        } else {
            throw new IllegalArgumentException(action);
        }
    }

    /**
     * Shared with {@link PackageReplacedReceiver}. Its dismiss actions are delivered to this
     * receiver.
     */
    @WorkerThread
    static void maybeShowInactivityNotification(final WalletApplication application,
            final Configuration config, final WalletData walletDataProvider) {
        if (!config.isTimeToRemindBalance())
            return;

        final Wallet wallet = walletDataProvider.getWallet();
        if (wallet == null) {
            // with version 7.0 and above it is possible to have the app installed without a wallet
            log.info("wallet does not exist, not showing inactivity warning");
            return;
        }
        final Coin estimatedBalance = wallet.getBalance(Wallet.BalanceType.ESTIMATED_SPENDABLE);
        if (!estimatedBalance.isPositive())
            return;

        log.info("detected balance, showing inactivity notification");

        final MonetaryFormat btcFormat = config.getFormat();
        final String title = application.getString(R.string.notification_inactivity_title);
        final StringBuilder text = new StringBuilder(application.getString(R.string.notification_inactivity_message,
                btcFormat.format(org.dash.wallet.common.money.Coin.valueOf(estimatedBalance.getValue()))));

        final NotificationCompat.Builder notification = new NotificationCompat.Builder(application,
                Constants.NOTIFICATION_CHANNEL_ID_TRANSACTIONS);
        notification.setStyle(new NotificationCompat.BigTextStyle().bigText(text));
        notification.setColor(application.getColor(R.color.fg_network_significant));
        notification.setSmallIcon(R.drawable.ic_dash_d_white);
        notification.setContentTitle(title);
        notification.setContentText(text);
        notification.setContentIntent(PendingIntent.getActivity(application, 0,
                OnboardingActivity.createIntent(application), PendingIntent.FLAG_IMMUTABLE));
        notification.setAutoCancel(true);

        final Intent dismissIntent = new Intent(application, BootstrapReceiver.class);
        dismissIntent.setAction(ACTION_DISMISS);
        notification.addAction(new NotificationCompat.Action.Builder(0,
                application.getString(R.string.notification_inactivity_action_dismiss),
                PendingIntent.getBroadcast(application, 0, dismissIntent, PendingIntent.FLAG_IMMUTABLE)).build());

        final Intent dismissForeverIntent = new Intent(application, BootstrapReceiver.class);
        dismissForeverIntent.setAction(ACTION_DISMISS_FOREVER);
        notification.addAction(new NotificationCompat.Action.Builder(0,
                application.getString(R.string.notification_inactivity_action_dismiss_forever),
                PendingIntent.getBroadcast(application, 0, dismissForeverIntent, PendingIntent.FLAG_IMMUTABLE)).build());

        final NotificationManager nm = application.getSystemService(NotificationManager.class);
        nm.notify(Constants.NOTIFICATION_ID_INACTIVITY, notification.build());
    }

    @WorkerThread
    private void dismissNotification(final Context context) {
        log.info("dismissing inactivity notification");
        config.setRemindBalanceTimeIn(DateUtils.DAY_IN_MILLIS);
        final NotificationManager nm = context.getSystemService(NotificationManager.class);
        nm.cancel(Constants.NOTIFICATION_ID_INACTIVITY);
    }

    @WorkerThread
    private void dismissNotificationForever(final Context context) {
        log.info("dismissing inactivity notification forever");
        config.setRemindBalanceTimeIn(DateUtils.WEEK_IN_MILLIS * 52);
        config.setRemindBalance(false);
        final NotificationManager nm = context.getSystemService(NotificationManager.class);
        nm.cancel(Constants.NOTIFICATION_ID_INACTIVITY);
    }

}
