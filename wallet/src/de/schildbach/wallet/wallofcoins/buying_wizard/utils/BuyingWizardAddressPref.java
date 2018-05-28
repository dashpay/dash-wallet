package de.schildbach.wallet.wallofcoins.buying_wizard.utils;

import android.content.SharedPreferences;

/**
 * Created by  on 13-Mar-18.
 */

public class BuyingWizardAddressPref {
    private final SharedPreferences prefs;
    private static final String PREF_BUY_COIN_ADDRESS = "PREF_BUY_COIN_ADDRESS";

    public BuyingWizardAddressPref(final SharedPreferences prefs) {
        this.prefs = prefs;
    }

    public void setBuyCoinAddress(String address) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(PREF_BUY_COIN_ADDRESS, address);
        editor.commit();
    }

    public String getBuyCoinAddress() {
        return prefs.getString(PREF_BUY_COIN_ADDRESS, "");
    }

    public void clearCoinAddress() {
        SharedPreferences.Editor editor = prefs.edit();
        editor.clear();
        editor.commit();
    }
}
