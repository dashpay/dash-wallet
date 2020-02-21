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

package de.schildbach.wallet;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.Spannable;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.RemoteViews;

import org.bitcoinj.core.Coin;
import org.bitcoinj.utils.Fiat;
import org.bitcoinj.utils.MonetaryFormat;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.Wallet.BalanceType;
import org.dash.wallet.common.Configuration;
import org.dash.wallet.common.util.GenericUtils;
import org.dash.wallet.common.util.MonetarySpannable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

import de.schildbach.wallet.rates.ExchangeRate;
import de.schildbach.wallet.ui.OnboardingActivity;
import de.schildbach.wallet.ui.QuickReceiveActivity;
import de.schildbach.wallet.ui.SendCoinsQrActivity;
import de.schildbach.wallet_test.R;

import static org.dash.wallet.common.Constants.PREFIX_ALMOST_EQUAL_TO;

/**
 * @author Andreas Schildbach
 */
public class WalletBalanceWidgetProvider extends AppWidgetProvider {

    private static final Logger log = LoggerFactory.getLogger(WalletBalanceWidgetProvider.class);

    private Coin getBalance(Context context) {
        final WalletApplication application = (WalletApplication) context.getApplicationContext();
        return application.getWallet().getBalance(BalanceType.ESTIMATED);
    }

    @Override
    public void onUpdate(final Context context, final AppWidgetManager appWidgetManager, final int[] appWidgetIds) {
        if (!walletNotReady(context)) {
            updateWidgets(context, appWidgetManager, appWidgetIds, getBalance(context));
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(final Context context, final AppWidgetManager appWidgetManager,
                                          final int appWidgetId, final Bundle newOptions) {
        if (!walletNotReady(context)) {
            updateWidget(context, appWidgetManager, appWidgetId, newOptions, getBalance(context));
        }
    }

    public static void updateWidgets(final Context context, final Wallet wallet) {
        final AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
        final ComponentName providerName = new ComponentName(context, WalletBalanceWidgetProvider.class);
        try {
            final int[] appWidgetIds = appWidgetManager.getAppWidgetIds(providerName);
            if (appWidgetIds.length > 0) {
                final Coin balance = wallet.getBalance(BalanceType.ESTIMATED);
                updateWidgets(context, appWidgetManager, appWidgetIds, balance);
            }
        } catch (final RuntimeException x) {// system server dead?
            log.warn("cannot update app widgets", x);
        }
    }

    private static void updateWidgets(final Context context, final AppWidgetManager appWidgetManager,
                                      final int[] appWidgetIds, final Coin balance) {
        for (final int appWidgetId : appWidgetIds) {
            final Bundle options = getAppWidgetOptions(appWidgetManager, appWidgetId);
            updateWidget(context, appWidgetManager, appWidgetId, options, balance);
        }
    }

    private static void updateWidget(final Context context, final AppWidgetManager appWidgetManager,
                                     final int appWidgetId, final Bundle appWidgetOptions, final Coin balance) {

        final RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.wallet_balance_widget_content);
        boolean walletNotReady = walletNotReady(context);
        views.setViewVisibility(R.id.main_pane, walletNotReady ? View.GONE : View.VISIBLE);
        views.setViewVisibility(R.id.wallet_not_initialized_message, walletNotReady ? View.VISIBLE : View.GONE);
        views.setOnClickPendingIntent(R.id.widget_button_balance,
                PendingIntent.getActivity(context, 0, OnboardingActivity.createIntent(context), 0));

        if (walletNotReady) {
            appWidgetManager.updateAppWidget(appWidgetId, views);
            return;
        }

        final Configuration config = new Configuration(PreferenceManager.getDefaultSharedPreferences(context),
                context.getResources());
        final MonetaryFormat btcFormat = config.getFormat();

        final Spannable balanceStr = new MonetarySpannable(btcFormat.noCode(), balance).applyMarkup(null,
                MonetarySpannable.STANDARD_INSIGNIFICANT_SPANS);

        new AsyncTask<Context, Void, ExchangeRate>() {
            @Override
            protected ExchangeRate doInBackground(Context... contexts) {
                return AppDatabase.getAppDatabase().exchangeRatesDao()
                        .getRateSync(config.getExchangeCurrencyCode());
            }

            @Override
            protected void onPostExecute(ExchangeRate exchangeRate) {
                super.onPostExecute(exchangeRate);

                final Spannable localBalanceStr;
                if (exchangeRate != null) {
                    org.bitcoinj.utils.ExchangeRate rate = new org.bitcoinj.utils.ExchangeRate(Coin.COIN,
                            exchangeRate.getFiat());
                    final Fiat localBalance = rate.coinToFiat(balance);
                    final MonetaryFormat localFormat = Constants.LOCAL_FORMAT.code(0,
                            PREFIX_ALMOST_EQUAL_TO + GenericUtils.currencySymbol(exchangeRate.getCurrencyCode()));
                    final Object[] prefixSpans = new Object[]{MonetarySpannable.SMALLER_SPAN,
                            new ForegroundColorSpan(context.getResources().getColor(R.color.fg_less_significant))};
                    localBalanceStr = new MonetarySpannable(localFormat, localBalance).applyMarkup(prefixSpans,
                            MonetarySpannable.STANDARD_INSIGNIFICANT_SPANS);
                } else {
                    localBalanceStr = null;
                }

                views.setTextViewText(R.id.widget_wallet_balance_btc, balanceStr);
                views.setViewVisibility(R.id.widget_wallet_balance_local, localBalanceStr != null ? View.VISIBLE : View.GONE);
                views.setTextViewText(R.id.widget_wallet_balance_local, localBalanceStr);

                if (appWidgetOptions != null) {
                    final int minWidth = appWidgetOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH);
                    views.setViewVisibility(R.id.widget_button_request, minWidth > 200 ? View.VISIBLE : View.GONE);
                    views.setViewVisibility(R.id.widget_button_send_qr, minWidth > 100 ? View.VISIBLE : View.GONE);
                }

                views.setOnClickPendingIntent(R.id.widget_button_balance,
                        PendingIntent.getActivity(context, 0, OnboardingActivity.createIntent(context), 0));
                views.setOnClickPendingIntent(R.id.widget_button_request,
                        PendingIntent.getActivity(context, 0, QuickReceiveActivity.createIntent(context), 0));
                views.setOnClickPendingIntent(R.id.widget_button_send_qr,
                        PendingIntent.getActivity(context, 0, SendCoinsQrActivity.createIntent(context, true), 0));

                appWidgetManager.updateAppWidget(appWidgetId, views);
            }
        }.execute(context);
    }

    private static Bundle getAppWidgetOptions(final AppWidgetManager appWidgetManager, final int appWidgetId) {
        try {
            final Method getAppWidgetOptions = AppWidgetManager.class.getMethod("getAppWidgetOptions", Integer.TYPE);
            final Bundle options = (Bundle) getAppWidgetOptions.invoke(appWidgetManager, appWidgetId);
            return options;
        } catch (final Exception x) {
            return null;
        }
    }

    private static boolean walletNotReady(Context context) {
        return ((WalletApplication) context.getApplicationContext()).getWallet() == null;
    }
}
