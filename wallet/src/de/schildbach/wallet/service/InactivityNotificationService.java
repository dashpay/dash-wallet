/*
 * Copyright 2016 the original author or authors.
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
import org.bitcoinj.utils.MonetaryFormat;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.Wallet.BalanceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.dash.wallet.common.Configuration;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.ui.OnboardingActivity;
import de.schildbach.wallet_test.R;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/**
 * This service is responsible for showing a notification if the user hasn't used the app for a longer time.
 *
 * @author Andreas Schildbach
 */
public final class InactivityNotificationService extends Service {
    public static void startMaybeShowNotification(final Context context) {
        ContextCompat.startForegroundService(context, new Intent(context, InactivityNotificationService.class));
    }

    private NotificationManager nm;
    private WalletApplication application;
    private Configuration config;
    private Wallet wallet;

    private static final String ACTION_DISMISS = InactivityNotificationService.class.getPackage().getName()
            + ".dismiss";
    private static final String ACTION_DISMISS_FOREVER = InactivityNotificationService.class.getPackage().getName()
            + ".dismiss_forever";

    private static final Logger log = LoggerFactory.getLogger(InactivityNotificationService.class);

    @Override
    public void onCreate() {
        super.onCreate();

        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        application = (WalletApplication) getApplication();
        config = application.getConfiguration();
        wallet = application.getWallet();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        if (ACTION_DISMISS.equals(intent.getAction()))
            handleDismiss();
        else if (ACTION_DISMISS_FOREVER.equals(intent.getAction()))
            handleDismissForever();
        else
            handleMaybeShowNotification();

        return START_REDELIVER_INTENT;
    }

    private void handleMaybeShowNotification() {
        final Coin estimatedBalance = wallet.getBalance(BalanceType.ESTIMATED_SPENDABLE);

        if (estimatedBalance.isPositive() && config.getRemindBackupSeed()) {
            log.info("detected balance, showing inactivity notification");
            final MonetaryFormat btcFormat = config.getFormat();
            final String title = getString(R.string.notification_inactivity_title);
            final StringBuilder text = new StringBuilder(
                    getString(R.string.notification_inactivity_message, btcFormat.format(estimatedBalance)));

            final Intent dismissIntent = new Intent(this, InactivityNotificationService.class);
            dismissIntent.setAction(ACTION_DISMISS);
            final Intent dismissForeverIntent = new Intent(this, InactivityNotificationService.class);
            dismissForeverIntent.setAction(ACTION_DISMISS_FOREVER);

            final NotificationCompat.Builder notification = new NotificationCompat.Builder(this,
                    Constants.NOTIFICATION_CHANNEL_ID_TRANSACTIONS);
            notification.setStyle(new NotificationCompat.BigTextStyle().bigText(text));
            notification.setSmallIcon(R.drawable.ic_dash_d_white_bottom);
            notification.setContentTitle(title);
            notification.setContentText(text);
            notification
                    .setContentIntent(PendingIntent.getActivity(this, 0, OnboardingActivity.createIntent(this), 0));
            notification.setAutoCancel(true);
            notification.addAction(new NotificationCompat.Action.Builder(0,
                    getString(R.string.notification_inactivity_action_dismiss_forever),
                    PendingIntent.getService(this, 0, dismissForeverIntent, 0)).build());
            notification.addAction(new NotificationCompat.Action.Builder(0,
                    getString(R.string.button_dismiss),
                    PendingIntent.getService(this, 0, dismissIntent, 0)).build());

            Notification inactivityNotification = notification.build();
            startForeground(Constants.NOTIFICATION_ID_INACTIVITY, inactivityNotification);
        } else {
            //startForeground is called here to prevent a crash that would happen by not calling
            //startForeground after calling a service with startForegroundService.
            final NotificationCompat.Builder invisibleNotification = new NotificationCompat.Builder(this,
                    Constants.NOTIFICATION_CHANNEL_ID_ONGOING);
            invisibleNotification.setPriority(Notification.PRIORITY_MIN);
            startForeground(Constants.NOTIFICATION_ID_INACTIVITY, invisibleNotification.build());

            new Handler().post(new Runnable() {
                @Override
                public void run() {
                    stopForeground(true);
                }
            });
        }
    }

    private void handleDismiss() {
        log.info("dismissing inactivity notification");
        stopForeground(true);
    }

    private void handleDismissForever() {
        log.info("dismissing inactivity notification forever");
        config.setRemindBalance(false);
        stopForeground(true);
    }
}
