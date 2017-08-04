package de.schildbach.wallet.response;

import android.util.Log;

public class GetCurrencyResp {

    public String code;
    public String name;
    public String symbol;

    @Override
    public String toString() {
//        return name + " (" + symbol + ")";
        return symbol;
    }


}
