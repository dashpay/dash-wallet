package de.schildbach.wallet.rates;

import android.arch.persistence.room.Entity;
import android.arch.persistence.room.PrimaryKey;
import android.support.annotation.NonNull;

import org.bitcoinj.utils.Fiat;

import java.math.BigDecimal;

/**
 * @author Samuel Barbosa
 */
@Entity(tableName = "exchange_rates")
public class ExchangeRate {

    @PrimaryKey
    @NonNull
    private String currencyCode;
    private String rate;

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
}
