package de.schildbach.wallet.wallofcoins.buyingwizard.utils;

import android.content.SharedPreferences;

/**
 * Created by  on 13-Mar-18.
 */

public class BuyDashAddressPref {
    private final SharedPreferences prefs;
    private static final String BUY_DASH_ADDRESS = "addres";

    public BuyDashAddressPref(final SharedPreferences prefs) {
        this.prefs = prefs;
    }

    public void setBuyDashAddress(String address) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(BUY_DASH_ADDRESS, address);
        editor.commit();
    }

    public String getBuyDashAddress() {
        return prefs.getString(BUY_DASH_ADDRESS, "");
    }

    public void clearBuyDashAddress() {
        SharedPreferences.Editor editor = prefs.edit();
        editor.clear();
        editor.commit();
    }
}
