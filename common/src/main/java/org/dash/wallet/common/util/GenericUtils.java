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

package org.dash.wallet.common.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.LocaleList;
import android.text.TextUtils;
import android.widget.Toast;

import java.util.Currency;
import java.util.Locale;

/**
 * @author Andreas Schildbach
 */
public class GenericUtils {
    public static boolean startsWithIgnoreCase(final String string, final String prefix) {
        return string.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    public static String currencySymbol(final String currencyCode) {
        try {
            final Currency currency = Currency.getInstance(currencyCode);
            return currency.getSymbol();
        } catch (final IllegalArgumentException x) {
            return currencyCode;
        }
    }

    public static boolean isInternetConnected(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        return cm.getActiveNetworkInfo() != null && cm.getActiveNetworkInfo().isConnected();
    }

    public static void showToast(Context context, String messages) {
        Toast.makeText(context, messages, Toast.LENGTH_LONG).show();
    }

    /**
     * Funtion which returns a concatenation of the currency code of the device's Locale
     * @return
     */
    public static String getCurrentCountryCurrencySymbol() {
        Locale defaultLocale = getDeviceLocale();
        Currency defaultCurrency = Currency.getInstance(defaultLocale);
        return defaultCurrency.getCurrencyCode();
    }

    /**
     * Funtion which returns a concatenation of the currency code together with the currency symbol
     * For currencies used by multiple countries, we set a locale with any country using the currency
     * If the currentCurrencySymbol equals the currency code, we just use the currency code, otherwise we
     * concatenate both
     * @param currencyCode
     * @return
     */
    public static String setCurrentCurrencySymbolWithCode(String currencyCode) {
        Locale currentLocale = new Locale("", "");
        String currentCurrencySymbol = "";
        switch (currencyCode.toLowerCase(Locale.ROOT)) {
            case "eur":
                currentLocale = Locale.FRANCE;
                break;
            case "xof":
                currentLocale = new Locale("fr", "CM");
                break;
            case "xaf":
                currentLocale = new Locale("fr", "SN");
                break;
            case "cfp":
                currentLocale = new Locale("fr", "NC");
                break;
            case "hkd":
                currentLocale = new Locale("en", "HK");
                break;
            case "bnd":
                currentLocale = new Locale("ms", "BN");
                break;
            case "aud":
                currentLocale = new Locale("en", "AU");
                break;
            case "gbp":
                currentLocale = Locale.UK;
                break;
            case "inr":
                currentLocale = new Locale("en", "IN");
                break;
            case "nzd":
                currentLocale = new Locale("en", "NZ");
                break;
            case "ils":
                currentLocale = new Locale("iw", "IL");
                break;
            case "jod":
                currentLocale = new Locale("ar", "JO");
                break;
            case "rub":
                currentLocale = new Locale("ru", "RU");
                break;
            case "zar":
                currentLocale = new Locale("en", "ZA");
                break;
            case "chf":
                currentLocale = new Locale("fr", "CH");
                break;
            case "try":
                currentLocale = new Locale("tr", "TR");
                break;
            case "usd":
                currentLocale = Locale.US;
                break;
        }
        currentCurrencySymbol = TextUtils.isEmpty(currentLocale.getLanguage()) ?
                currencySymbol(currencyCode.toLowerCase(Locale.ROOT)) : Currency.getInstance(currentLocale).getSymbol();

        return currencyCode.equalsIgnoreCase(currentCurrencySymbol) ? currencyCode :
                String.format(getDeviceLocale(), "%s %s", currencyCode, currentCurrencySymbol);
    }

    public static Locale getDeviceLocale() {
        String countryCode = "";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            countryCode = LocaleList.getDefault().get(0).getCountry();
        } else {
            countryCode = Locale.getDefault().getCountry();
        }
        String deviceLocaleLanguage = Locale.getDefault().getLanguage();
        return new Locale(deviceLocaleLanguage, countryCode);
    }
}
