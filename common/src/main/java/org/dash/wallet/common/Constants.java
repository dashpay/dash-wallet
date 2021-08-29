package org.dash.wallet.common;

import org.bitcoinj.core.Coin;
import org.bitcoinj.params.MainNetParams;

public class Constants {

    public static final char CHAR_HAIR_SPACE = '\u200a';
    public static final char CHAR_THIN_SPACE = '\u2009';
    public static final char CHAR_ALMOST_EQUAL_TO = '\u2248';
    public static final char CHAR_CHECKMARK = '\u2713';
    public static final char CURRENCY_PLUS_SIGN = '\uff0b';
    public static final char CURRENCY_MINUS_SIGN = '\uff0d';
    public static final String PREFIX_ALMOST_EQUAL_TO = Character.toString(CHAR_ALMOST_EQUAL_TO) + CHAR_THIN_SPACE;

    public static Coin MAX_MONEY = MainNetParams.get().getMaxMoney();

    public static final int REQUEST_CODE_BUY_SELL = 100;
    public static final int USER_BUY_SELL_DASH = 101;
    public static final int RESULT_CODE_GO_HOME = 100;
}
