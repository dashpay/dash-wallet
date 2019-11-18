package de.schildbach.wallet.rates;

import android.os.Build;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

import org.bitcoinj.utils.Fiat;

import java.math.BigDecimal;
import java.util.Currency;

/**
 * @author Samuel Barbosa
 */
@Entity(tableName = "exchange_rates")
public class ExchangeRate {

    @PrimaryKey
    @NonNull
    private String currencyCode;
    private String rate;

    @Ignore
    private String currencyName;
    @Ignore
    private Currency currency;

    public ExchangeRate(@NonNull String currencyCode, String rate) {
        this.currencyCode = currencyCode;
        this.rate = rate;
    }

    @NonNull
    public String getCurrencyCode() {
        return currencyCode;
    }

    public String getRate() {
        return rate;
    }

    public void setRate(String rate) {
        this.rate = rate;
    }

    public Fiat getFiat() {
        final long val = new BigDecimal(rate).movePointRight(Fiat.SMALLEST_UNIT_EXPONENT).longValue();
        return Fiat.valueOf(currencyCode, val);
    }

    @Override
    public String toString() {
        return "{" + currencyCode + ":" + rate + "}";
    }

    private Currency getCurrency() {
        if (currency == null) {
            currency = Currency.getInstance(currencyCode.toUpperCase());
        }
        return currency;
    }

    public String getCurrencyName() {
        //VES special case
        if (currencyCode.equalsIgnoreCase("VES")) {
            return "Venezuelan Bolívar";
        }

        if (currencyName == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                currencyName = getCurrency().getDisplayName();
            } else {
                currencyName = "";
            }
        }
        return currencyName;
    }

}
