package org.dash.wallet.common;

import org.bitcoinj.core.Coin;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.utils.MonetaryFormat;
import org.dash.wallet.common.util.GenericUtils;

public class Constants {

    public static final char CHAR_HAIR_SPACE = '\u200a';
    public static final char CHAR_THIN_SPACE = '\u2009';
    public static final char CHAR_ALMOST_EQUAL_TO = '\u2248';
    public static final char CHAR_CHECKMARK = '\u2713';
    public static final char CURRENCY_PLUS_SIGN = '\uff0b';
    public static final char CURRENCY_MINUS_SIGN = '\uff0d';
    public static final String PREFIX_ALMOST_EQUAL_TO = Character.toString(CHAR_ALMOST_EQUAL_TO) + CHAR_THIN_SPACE;

    public static final int REQUEST_CODE_BUY_SELL = 100;
    public static final int USER_BUY_SELL_DASH = 101;
    public static final int RESULT_CODE_GO_HOME = 100;
    public static final int COIN_BASE_AUTH = 102;

    public static Coin MAX_MONEY = MainNetParams.get().getMaxMoney();
    public static final Coin ECONOMIC_FEE = Coin.valueOf(1000);
    public static final MonetaryFormat SEND_PAYMENT_LOCAL_FORMAT = new MonetaryFormat().withLocale(GenericUtils.getDeviceLocale()).minDecimals(2).optionalDecimals();

    public static String EXPLORE_GC_FILE_PATH;
}
