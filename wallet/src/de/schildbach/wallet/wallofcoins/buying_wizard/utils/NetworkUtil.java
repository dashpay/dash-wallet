package de.schildbach.wallet.wallofcoins.buying_wizard.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

/**
 * Created on 15-Mar-18.
 */

public class NetworkUtil {
    public static boolean isOnline(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = cm.getActiveNetworkInfo();
        /* NetworkInfo mWifi = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);       if(netInfo.equals(mWifi)){           if(mWifi.isAvailable())               return true;           else               return false;       }else{           return true;       }*/
        return netInfo != null && netInfo.isConnectedOrConnecting();
    }


}
