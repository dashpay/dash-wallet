package de.schildbach.wallet.wallofcoins.selling_wizard.utils;

import android.util.Log;

import de.schildbach.wallet_test.BuildConfig;

/**
 * Created on 05-Apr-18.
 */

public class WOCLogUtil {


    public static void showLogError(String tag, String log) {
        if (BuildConfig.DEBUG)
            Log.e(tag, log);
    }

    public static void showLogWarning(String tag, String log) {
        if (BuildConfig.DEBUG)
            Log.w(tag, log);
    }

    public static void showLogInfo(String tag, String log) {
        if (BuildConfig.DEBUG)
            Log.i(tag, log);
    }

    public static void showLogDebug(String tag, String log) {
        if (BuildConfig.DEBUG)
            Log.d(tag, log);
    }
}
