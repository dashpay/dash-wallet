package de.schildbach.wallet.wallofcoins.selling_wizard.utils;

import android.content.SharedPreferences;

/**
 * Created on 13-Mar-18.
 */

public class SellingWizardAddressPref {
    private final SharedPreferences prefs;
    private static final String PREF_SELL_COIN_ADDRESS = "PREF_SELL_COIN_ADDRESS";

    public SellingWizardAddressPref(final SharedPreferences prefs) {
        this.prefs = prefs;
    }

    public void setSellCoinAddress(String address) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(PREF_SELL_COIN_ADDRESS, address);
        editor.commit();
    }

    public String getSellCoinAddress() {
        return prefs.getString(PREF_SELL_COIN_ADDRESS, "");
    }

    public void clearSellCoinAddress() {
        SharedPreferences.Editor editor = prefs.edit();
        editor.clear();
        editor.commit();
    }
}
