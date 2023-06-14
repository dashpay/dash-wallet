///*
// * Copyright 2011-2015 the original author or authors.
// *
// * This program is free software: you can redistribute it and/or modify
// * it under the terms of the GNU General Public License as published by
// * the Free Software Foundation, either version 3 of the License, or
// * (at your option) any later version.
// *
// * This program is distributed in the hope that it will be useful,
// * but WITHOUT ANY WARRANTY; without even the implied warranty of
// * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// * GNU General Public License for more details.
// *
// * You should have received a copy of the GNU General Public License
// * along with this program.  If not, see <http://www.gnu.org/licenses/>.
// */
//
//package org.dash.wallet.common.util;
//
//import android.content.Context;
//import android.graphics.drawable.Drawable;
//import android.text.Spannable;
//import android.text.SpannableStringBuilder;
//import android.text.style.ImageSpan;
//
//import androidx.core.content.res.ResourcesCompat;
//
//import org.dash.wallet.common.R;
//
//import android.net.ConnectivityManager;
//import android.os.Build;
//import android.os.LocaleList;
//import android.text.TextUtils;
//import android.widget.Toast;
//
//import org.bitcoinj.utils.Fiat;
//import org.bitcoinj.utils.MonetaryFormat;
//import org.dash.wallet.common.Constants;
//import org.dash.wallet.common.data.CurrencyInfo;
//
//import java.text.NumberFormat;
//import java.util.Currency;
//import java.util.Locale;
//import java.util.Objects;
//
///**
// * @author Andreas Schildbach
// */
//public class GenericUtils { TODO
//
//    public static boolean startsWithIgnoreCase(final String string, final String prefix) {
//        return string.regionMatches(true, 0, prefix, 0, prefix.length());
//    }
//
//    public static String currencySymbol(final String currencyCode) {
//        try {
//            final Currency currency = Currency.getInstance(currencyCode);
//            return currency.getSymbol();
//        } catch (final IllegalArgumentException x) {
//            return currencyCode;
//        }
//    }
//
//    public static Spannable appendDashSymbol(Context context, CharSequence text, boolean spaceBefore, boolean spaceAfter, float scale) {
//        return insertDashSymbol(context, text, text.length(), spaceBefore, spaceAfter, scale);
//    }
//
//    public static Spannable insertDashSymbol(Context context, CharSequence text, int position, boolean spaceBefore, boolean spaceAfter, float scale) {
//
//        Drawable drawableDash = ResourcesCompat.getDrawable(context.getResources(), R.drawable.ic_dash_d_black, null);
//        if (drawableDash == null) {
//            return null;
//        }
//        int size = (int) (scale * 32);
//        drawableDash.setBounds(0, 0, size, size);
//        ImageSpan dashSymbol = new ImageSpan(drawableDash, ImageSpan.ALIGN_BASELINE);
//
//        SpannableStringBuilder builder = new SpannableStringBuilder(text);
//        if (spaceBefore) {
//            builder.insert(position++, " ");
//        }
//        builder.insert(position, " ");
//        if (spaceAfter) {
//            builder.insert(position + 1, " ");
//        }
//        builder.setSpan(dashSymbol, position, position + 1, 0);
//
//        return builder;
//    }
//
//    public static boolean isInternetConnected(Context context) {
//        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
//
//        return cm.getActiveNetworkInfo() != null && cm.getActiveNetworkInfo().isConnected();
//    }
//
//    public static void showToast(Context context, String messages) {
//        Toast.makeText(context, messages, Toast.LENGTH_LONG).show();
//    }
//
//    /**
//     * Function which returns a concatenation of the currency code or currency symbol
//     * For currencies used by multiple countries, we set a locale with any country using the currency
//     * If the currentCurrencySymbol equals the currency code, we just use the currency code, otherwise we
//     * get the symbol
//     * @param currencyCode
//     * @return
//     */
//    public static String setCurrentCurrencySymbolWithCode(String currencyCode) {
//        Locale currentLocale = new Locale("", "");
//        String currentCurrencySymbol = "";
//        switch (currencyCode.toLowerCase(Locale.ROOT)) {
//            case "eur":
//                currentLocale = Locale.FRANCE;
//                break;
//            case "xof":
//                currentLocale = new Locale("fr", "CM");
//                break;
//            case "xaf":
//                currentLocale = new Locale("fr", "SN");
//                break;
//            case "cfp":
//                currentLocale = new Locale("fr", "NC");
//                break;
//            case "hkd":
//                currentLocale = new Locale("en", "HK");
//                break;
//            case "bnd":
//                currentLocale = new Locale("ms", "BN");
//                break;
//            case "aud":
//                currentLocale = new Locale("en", "AU");
//                break;
//            case "gbp":
//                currentLocale = Locale.UK;
//                break;
//            case "inr":
//                currentLocale = new Locale("en", "IN");
//                break;
//            case "nzd":
//                currentLocale = new Locale("en", "NZ");
//                break;
//            case "ils":
//                currentLocale = new Locale("iw", "IL");
//                break;
//            case "jod":
//                currentLocale = new Locale("ar", "JO");
//                break;
//            case "rub":
//                currentLocale = new Locale("ru", "RU");
//                break;
//            case "zar":
//                currentLocale = new Locale("en", "ZA");
//                break;
//            case "chf":
//                currentLocale = new Locale("fr", "CH");
//                break;
//            case "try":
//                currentLocale = new Locale("tr", "TR");
//                break;
//            case "usd":
//                currentLocale = Locale.US;
//                break;
//        }
//        currentCurrencySymbol = TextUtils.isEmpty(currentLocale.getLanguage()) ?
//                currencySymbol(currencyCode.toLowerCase(Locale.ROOT)) : Currency.getInstance(currentLocale).getSymbol();
//
//        return String.format(getDeviceLocale(), "%s",
//                currencyCode.equalsIgnoreCase(currentCurrencySymbol) ? currencyCode : currentCurrencySymbol);
//    }
//
//    public static Locale getDeviceLocale() {
//        String countryCode = "";
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
//            countryCode = LocaleList.getDefault().get(0).getCountry();
//        } else {
//            countryCode = Locale.getDefault().getCountry();
//        }
//        String deviceLocaleLanguage = Locale.getDefault().getLanguage();
//        return new Locale(deviceLocaleLanguage, countryCode);
//    }
//
//    public static FiatAmountFormat formatFiatFromLocale(CharSequence fiatValue) {
//        String valWithoutLetters = stripLettersFromString(fiatValue.toString());
//        String valWithoutComma = formatFiatWithoutComma(valWithoutLetters);
//        Double fiatAsDouble;
//        // we may get a NumberFormatException
//        try {
//            fiatAsDouble = valWithoutComma.length() == 0 ? 0.00 : Double.parseDouble(valWithoutComma);
//        } catch (NumberFormatException x) {
//            fiatAsDouble = 0.00;
//        }
//        NumberFormat numberFormat = NumberFormat.getCurrencyInstance(getDeviceLocale());
//        String formattedStringValue = numberFormat.format(fiatAsDouble);
//        // get currency symbol and code to remove explicitly
//        String currencyCode = numberFormat.getCurrency().getCurrencyCode();
//        String currencySymbol = numberFormat.getCurrency().getSymbol();
//        return new FiatAmountFormat(Character.isDigit(formattedStringValue.charAt(0)), stripCurrencyFromString(formattedStringValue, currencySymbol, currencyCode));
//    }
//
//    /**
//     * Keep numericals, minus, dot, comma
//     */
//    private static String stripLettersFromString(String st) {
//        return st.replaceAll("[^\\d,.-]", "");
//    }
//
//    /**
//     * Remove currency symbols and codes from the string
//     */
//    private static String stripCurrencyFromString(String st, String symbol, String code) {
//        return stripLettersFromString(st.replaceAll(symbol, "").replaceAll(code, ""));
//    }
//
//    /**
//     * To perform some operations on our fiat values (ex: parse to double, convert fiat to Coin), it needs to be properly formatted
//     * In case our fiat value is in a currency that has a comma, we need to strip it away so as to have our value as a decimal
//     * @param fiatValue
//     * @return
//     */
//    public static String formatFiatWithoutComma(String fiatValue){
//        boolean fiatValueContainsCommaWithDecimal = fiatValue.contains(",") && fiatValue.contains(".");
//        return fiatValueContainsCommaWithDecimal ? fiatValue.replaceAll(",", "") :  fiatValue.replaceAll(",", ".");
//    }
//
//    public static String fiatToString(Fiat fiat) {
//        MonetaryFormat format = Constants.SEND_PAYMENT_LOCAL_FORMAT.noCode();
//        NumberFormat numberFormat = NumberFormat.getCurrencyInstance(getDeviceLocale());
//        Currency currency = Currency.getInstance(fiat.currencyCode);
//        numberFormat.setCurrency(currency);
//        String currencySymbol = currency.getSymbol(getDeviceLocale());
//        boolean isCurrencyFirst = numberFormat.format(1.0).startsWith(currencySymbol);
//
//        if (isCurrencyFirst) {
//            return currencySymbol + " " + format.format(fiat);
//        } else {
//            return format.format(fiat) + " " + currencySymbol;
//        }
//    }
//
//    public static boolean isCurrencyFirst(Fiat fiat) {
//        NumberFormat numberFormat = NumberFormat.getCurrencyInstance(getDeviceLocale());
//        Currency currency = Currency.getInstance(fiat.currencyCode);
//        numberFormat.setCurrency(currency);
//        String currencySymbol = currency.getSymbol(getDeviceLocale());
//       return numberFormat.format(1.0).startsWith(currencySymbol);
//    }
//
//    public static String getLocalCurrencySymbol(String currencyCode) {
//        NumberFormat numberFormat = NumberFormat.getCurrencyInstance(getDeviceLocale());
//        Currency currency = Currency.getInstance(currencyCode);
//        numberFormat.setCurrency(currency);
//        return currency.getSymbol(getDeviceLocale());
//    }
//
//    public static String fiatToStringWithoutCurrencyCode(Fiat fiat) {
//        MonetaryFormat format = Constants.SEND_PAYMENT_LOCAL_FORMAT.noCode();
//        return  format.format(fiat).toString();
//    }
//
//    public static String getCoinIcon(String code) {
//        return  "https://raw.githubusercontent.com/jsupa/crypto-icons/main/icons/"+code.toLowerCase()+".png";
//    }
//
//    public static String getLocaleCurrencyCode(){
//        Currency currency = Currency.getInstance(getDeviceLocale());
//        String newCurrencyCode = currency.getCurrencyCode();
//        if (CurrencyInfo.hasObsoleteCurrency(newCurrencyCode)) {
//            newCurrencyCode = CurrencyInfo.getUpdatedCurrency(newCurrencyCode);
//        }
//        newCurrencyCode = CurrencyInfo.getOtherName(newCurrencyCode);
//        return newCurrencyCode;
//    }
//}
