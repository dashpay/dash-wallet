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
import org.bitcoinj.script.Script;
import org.bitcoinj.utils.ContextPropagatingThreadFactory;
import org.bitcoinj.utils.MonetaryFormat;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.WalletExtension;
import org.bitcoinj.wallet.authentication.AuthenticationGroupExtension;
import org.dash.wallet.common.WalletDataProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.dash.wallet.common.Configuration;

import dagger.hilt.android.AndroidEntryPoint;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.ui.OnboardingActivity;
import de.schildbach.wallet_test.R;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.text.format.DateUtils;

import androidx.annotation.WorkerThread;
import androidx.core.app.NotificationCompat;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.inject.Inject;

/**
 * @author Andreas Schildbach
 */
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
    protected WalletDataProvider walletDataProvider;
    @Inject
    protected WalletApplication application;
    @Inject
    protected Configuration configuration;

    @Override
    public void onReceive(final Context context, final Intent intent) {
        log.info("got broadcast: " + intent);
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
        final boolean bootCompleted = Intent.ACTION_BOOT_COMPLETED.equals(action);
        final boolean packageReplaced = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);

        if (packageReplaced || bootCompleted) {
            // make sure wallet is upgraded to HD
            if (packageReplaced)
                maybeUpgradeWallet(walletDataProvider.getWallet());

            // make sure there is always a blockchain sync scheduled
            application.startBlockchainService(false);


            // if the app hasn't been used for a while and contains coins, maybe show reminder
            maybeShowInactivityNotification();
            application.myPackageReplaced = true;

            // reset the notification explainer flag
            if (packageReplaced)
                configuration.setShowNotificationsExplainer(true);
        } else if (ACTION_DISMISS.equals(action)) {
            dismissNotification(context);
        } else if (ACTION_DISMISS_FOREVER.equals(action)) {
            dismissNotificationForever(context);
        } else {
            throw new IllegalArgumentException(action);
        }
    }

    @WorkerThread
    private void maybeUpgradeWallet(final Wallet wallet) {
        log.info("maybe upgrading wallet");

        if (wallet == null) {
            // with version 7.0 and above it is possible to have the app installed without a wallet
            log.info("wallet does not exist, not upgrading the wallet file");
            return;
        }

        // Maybe upgrade wallet from basic to deterministic, and maybe upgrade to the latest script type
        if (wallet.isDeterministicUpgradeRequired(Script.ScriptType.P2PKH) && !wallet.isEncrypted()) {
            // upgrade from v1 wallet to v4 wallet
            wallet.upgradeToDeterministic(Script.ScriptType.P2PKH, null);
        }

        // for upgrades from version 9.0.0 to 9.0.3, there is a bug that requires rescanning
        // the transactions for protx related items
        Map<String, WalletExtension> extensions = wallet.getExtensions();
        WalletExtension extension = extensions.get(AuthenticationGroupExtension.EXTENSION_ID);
        if (extension != null) {
            // reset will rescan existing transactions and rebuild the authentication usage info
            ((AuthenticationGroupExtension) extension).reset();
        }

        // Maybe upgrade wallet to secure chain
        try {
            wallet.doMaintenance(null, false);
        } catch (final Exception x) {
            log.error("failed doing wallet maintenance", x);
        }
    }

    @WorkerThread
    private void maybeShowInactivityNotification() {
        if (!config.isTimeToRemindBalance())
            return;

        final Wallet wallet = walletDataProvider.getWallet();
        if (wallet == null)
            return;
        final Coin estimatedBalance = wallet.getBalance(Wallet.BalanceType.ESTIMATED_SPENDABLE);
        if (!estimatedBalance.isPositive())
            return;

        log.info("detected balance, showing inactivity notification");

        final MonetaryFormat btcFormat = config.getFormat();
        final String title = application.getString(R.string.notification_inactivity_title);
        final StringBuilder text = new StringBuilder(application.getString(R.string.notification_inactivity_message,
                btcFormat.format(estimatedBalance)));

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
