/*
 * Copyright 2019 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.dash.wallet.common.data;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;

import org.dash.wallet.common.R;

import java.util.Currency;
import java.util.HashMap;

/**
    @author: Eric Britten
 */

public class CurrencyInfo {

    /*
        Older android devices will have obsolete currency codes
        that do not match what the prices sources currently used.

        e.g. in Belarus (BY), the currency changed from BYR to BYN
        at the end of 2016.  Older phones have BYR.

        This app will read BYR from the device and then query BYN
        in the exchange rates list, but must get the name of BYR
        from the device.
     */
    private static HashMap<String, String> obsoleteCurrencyMap;

    /*
        These currencies are listed in the price data, but are not
        ISO 4217 currency codes.  This will map those codes to the
        currency names.
     */
    private static HashMap<String, Integer> otherCurrencyMap;

    /*
        These currencies are listed in the price data and have the
        same name as a different currency.  This differs from
        obsoleteCurrencyMap because both of these codes exist in the
        price sources.

        e.g CNH vs CNY (ISO 4217)
     */
    private static HashMap<String, String> useOtherNameMap;


    static {
        obsoleteCurrencyMap = new HashMap<>();
        obsoleteCurrencyMap.put("BYR", "BYN"); // Belarus Ruble, changed in 2016
        obsoleteCurrencyMap.put("MRO", "MRU"); // Mauritania Ouguiya, changed in 2018

        otherCurrencyMap = new HashMap<>();
        otherCurrencyMap.put("GGP", R.string.currency_GGP);
        otherCurrencyMap.put("JEP", R.string.currency_JEP);
        otherCurrencyMap.put("IMP", R.string.currency_IMP);
        otherCurrencyMap.put("USDC", R.string.currency_USDC);
        otherCurrencyMap.put("LTC", R.string.currency_LTC);
        otherCurrencyMap.put("ETH", R.string.currency_ETH);
        otherCurrencyMap.put("BTC", R.string.currency_BTC);
        otherCurrencyMap.put("PAX", R.string.currency_PAX);
        otherCurrencyMap.put("GUSD", R.string.currency_GUSD);

        useOtherNameMap = new HashMap<>();
        useOtherNameMap.put("CNH", "CNY");
        useOtherNameMap.put("VES", "VEF");
    }

    public static boolean hasObsoleteCurrency(String code) {
        return obsoleteCurrencyMap.containsKey(code);
    }

    public static String getUpdatedCurrency(String code) {
        return obsoleteCurrencyMap.get(code);
    }

    // convert an updated code to an obsolete code
    public static String getObsoleteCurrency(String updatedCode) {
        for (String obsoleteCode : obsoleteCurrencyMap.keySet()) {
            if(obsoleteCurrencyMap.get(obsoleteCode).equals(updatedCode)) {
                return obsoleteCode;
            }
        }
        return null;
    }

    // obtain a currency name for those codes for which the local has no information
    public static String getOtherCurrencyName(String currencyCode, Context context) {
        String currencyName = "";
        currencyCode = currencyCode.toUpperCase();

        String oldCurrencyCode = CurrencyInfo.getObsoleteCurrency(currencyCode);
        if(oldCurrencyCode != null) {
            currencyName = getCurrencyNameFromCode(oldCurrencyCode);
        } else {
            // handle cases where the phone doesn't have currency information

            // for CNH, use the name for CNY
            if(useOtherNameMap.containsKey(currencyCode)) {
                String useNameOfCode = useOtherNameMap.get(currencyCode);
                currencyName = getCurrencyNameFromCode(useNameOfCode);
            } else if(otherCurrencyMap.containsKey(currencyCode)) {
                Integer id = otherCurrencyMap.get(currencyCode);
                if (id != null)
                    currencyName = context.getString(id);
            }
        }

        return currencyName;
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    public static String getCurrencyNameFromCode(String code) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            Currency oldCurrency = Currency.getInstance(code.toUpperCase());
            return oldCurrency.getDisplayName();
        } else {
            return "";
        }
    }
}
